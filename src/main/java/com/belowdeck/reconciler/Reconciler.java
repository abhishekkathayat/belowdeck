package com.belowdeck.reconciler;

import com.belowdeck.model.*;

import java.util.*;

/**
 * The heart of the tool: turns three independently-collected snapshots (NMT, smaps, cgroup)
 * into one reconciled picture of where a Java process's resident memory actually lives.
 * Implements the algorithm from KT doc S12.2.
 *
 * <h2>The core idea: an interval-containment join</h2>
 * NMT reservations are large virtual-address ranges; the JVM commits many smaller sub-ranges
 * inside them, and smaps reports those as many individual VMAs. So attributing "resident memory
 * per JVM subsystem" is fundamentally a <b>1-to-many containment join</b>: each NMT reservation
 * contains zero or more smaps VMAs, and we want to sum, per reservation category, the resident
 * memory (PSS) of every VMA it contains.
 *
 * <pre>
 *     1. Build an interval tree from NMT reservations: [base, base+size) -&gt; category
 *     2. For every smaps VMA:
 *          lookup which NMT reservation (if any) contains VMA.start
 *          if found: attribute VMA.Pss to that reservation's category
 *          if not found: the VMA is part of "the gap" - fingerprint it (GapClassifier)
 *     3. Aggregate: per-cateory resident totals, and per-gap-bucket resident totals
 * </pre>
 *
 * <h2>Why PSS, not RSS</h2>
 * Every sum in this class is over {@code Pss}, never {@code Rss}. This is deliberate and important:
 * summing RSS across VMAs multi-counts any page that's mapped more than once in the same process,
 * which is exactly what ZGC's colored-pointer heap does (KT doc S6.4) - a ZGC heap would appear to
 * be ~3x its real size if we summed RSS. PSS divides each shared page by its mapping count, so the
 * ZGC heap "self-corrects" back to roughly its true size when summed. See KT doc S7.2.
 *
 * <h2>Edge cases handled</h2>
 * <ul>
 *     <li><b>Nested/overlapping NMT reservations:</b> {@link IntervalTree#queryMostSpecific(long)}
 *          picks the smallest contain interval, per KT doc S12.2.</li>
 *     <li><b>glibc arena clustering:</b> a VMA can't be confidently called part of a malloc-arena
 *          cluster in isolation - see {@link #confirmOrDemoteArenaCandidates}.</li>
 *     <li><b>Partial overlap</b> (a VMA straddling a reservation boundary): we attribute by the
 *          VMA's <i>start</i> address only, matching the KT doc's explicitly-accepted approximation
 *          ("attribute by which region contains its start; simpler; document the approximation") rather
 *          than splitting PSS proportionally across the boundary.</li>
 * </ul>
 */
public class Reconciler {

    /**
     * How many 64&nbsp;MB-aligned anonymous mappings must appear before we're willing to call them a
     * glibc malloc arean cluster rather than coincidence. A real glibc arena bloat incident typically
     * involves many such mappings (KT doc S10.1 gives an example of up to 128 on a 16-core box); 2 is a
     * conservative floor that still avoids flagging a single large one-off allocation as "an arena".
     */
    private static final int MIN_ARENA_CLUSTER_SIZE = 2;

    /**
     * Run the full reconciliation.
     */
    public ReconciliationResult reconcile(
            NmtSnapshot nmt,
            List<SmapsVma> vmas,
            CgroupStats cgroupStats,
            ProcStatus procStatus,
            HeapInfo heapInfo
    ) {
        IntervalTree tree = buildIntervalTree(nmt.regions());

        Map<String, CategoryAccumulator> accumulators = new LinkedHashMap<>();
        List<GapEntry> gapEntries = new ArrayList<>();
        long totalPssBytes = 0;

        for (SmapsVma vma : vmas) {
            long pssBytes = vma.pssKb() * 1024L;
            totalPssBytes += pssBytes;

            Optional<IntervalTree.Interval> owner = tree.queryMostSpecific(vma.start());
            if (owner.isPresent()) {
                accumulators.computeIfAbsent(owner.get().category(), unused -> new CategoryAccumulator()).add(vma);
            } else {
                gapEntries.add(new GapEntry(vma, GapClassifier.classify(vma), null));
            }
        }
        gapEntries = confirmOrDemoteArenaCandidates(gapEntries);

        List<CategoryUsage> jvmTrackedCategories = buildCategoryUsageList(accumulators, nmt.categoryTotals());
        long jvmTrackedPssBytes = jvmTrackedCategories.stream().mapToLong(CategoryUsage::residentPssBytes).sum();

        Map<GapCategory, Long> gapTotalsByCategory = new EnumMap<>(GapCategory.class);
        long gapTotalPssBytes = 0;
        for (GapEntry entry : gapEntries) {
            long pssBytes = entry.vma().pssKb() * 1024L;
            gapTotalsByCategory.merge(entry.category(), pssBytes, Long::sum);
            gapTotalPssBytes += pssBytes;
        }

        return new ReconciliationResult(
                totalPssBytes,
                jvmTrackedCategories,
                jvmTrackedPssBytes,
                gapEntries,
                gapTotalsByCategory,
                gapTotalPssBytes,
                cgroupStats,
                procStatus,
                heapInfo,
                nmt.threadCount()
        );
    }

    private IntervalTree buildIntervalTree(List<NmtRegion> regions) {
        IntervalTree tree = new IntervalTree();
        for (NmtRegion region : regions) {
            tree.insert(region.baseAddress(), region.endAddress(), region.category());
        }
        return tree;
    }

    /**
     * Second classification pass: {@link GapClassifier} can only provisionally
     * tag a 64&nbsp;MB-aligned anonymous mapping as {@link GapCategory#ARENA_CANDIDATE},
     * because whether it's really part of an arena cluster depends on how many
     * similar mappings exist elsewhere in the gap - information only available once
     * every VMA has been classified. Here we count the candidates: enough of them confirms
     * a real glibc malloc arena cluster (KT doc S10.1, "then single highest-value thing
     * your total can do"); too few and we back off to an honest {@link GapCategory#UNKNOWN}
     * rather than over-claiming.
     */
    private List<GapEntry> confirmOrDemoteArenaCandidates(List<GapEntry> entries) {
        long candidateCount = entries.stream().filter(e -> e.category() == GapCategory.ARENA_CANDIDATE).count();
        if (candidateCount == 0) {
            return entries;
        }
        boolean confirmed = candidateCount >= MIN_ARENA_CLUSTER_SIZE;
        GapCategory resolvedCategory = confirmed ? GapCategory.GLIBC_MALLOC_ARENA : GapCategory.UNKNOWN;
        String note = confirmed ?
                "Part of a cluster of " + candidateCount + " 64MB-alighed anonymous mappings - " +
                "the classic glibc malloc arean signature (KT doc S10.1). Consider MALLOC_ARENA_MAX " +
                "or switching allocators (jemalloc/tcmalloc)."
                : "A single 64MB-aligned anonymous mapping - on its own this isn't strong enough evidence " +
                "of a glibc arena cluster to call it one confidently.";

        List<GapEntry> resolved = new ArrayList<>(entries.size());
        for (GapEntry entry : entries) {
            if (entry.category() == GapCategory.ARENA_CANDIDATE) {
                resolved.add(entry.withCategory(resolvedCategory, note));
            } else {
                resolved.add(entry);
            }
        }
        return resolved;
    }

    private List<CategoryUsage> buildCategoryUsageList(
            Map<String, CategoryAccumulator> accumulators,
            Map<String, NmtCategoryTotal> nmtTotals
    ) {
        List<CategoryUsage> result = new ArrayList<>();
        for (Map.Entry<String, CategoryAccumulator> entry : accumulators.entrySet()) {
            String category = entry.getKey();
            CategoryAccumulator accumulator = entry.getValue();
            NmtCategoryTotal total = nmtTotals.get(category);
            result.add(new CategoryUsage(
                    category,
                    accumulator.pssBytes,
                    accumulator.vmaCount,
                    accumulator.privateDirtyBytes,
                    total != null ? total.reservedBytes() : 0,
                    total != null ? total.committedBytes() : 0
            ));
        }
        // Biggest contributors first - this is what makes the report scannable.
        result.sort(Comparator.comparingLong(CategoryUsage::residentPssBytes).reversed());
        return result;
    }

    /** Mutable running totals for one NMT category while walking the VMA list; frozen into a {@link CategoryUsage} at the end. */
    private static final class CategoryAccumulator {
        long pssBytes;
        int vmaCount;
        long privateDirtyBytes;

        void add(SmapsVma vma) {
            pssBytes += vma.pssKb() * 1024L;
            privateDirtyBytes += vma.privateDirtyKb() * 1024L;
            vmaCount++;
        }
    }
}
