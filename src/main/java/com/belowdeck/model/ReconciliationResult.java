package com.belowdeck.model;

import java.util.List;
import java.util.Map;

/**
 * The complete output of the reconciler: every smaps VMA sorted into either a
 * JVM-tracked category (via the NMT interval-tree join) or a gap bucket, plus
 * the container-level and heap-level context needed to render the "waterfall"
 * report from KT doc S12.4.
 *
 * <p>This is the single object that flows from {@code Reconciler} into
 * {@code HeuristicEngine} and {@code WaterfallReportRenderer} - everything
 * either of those needs to do their job lives here.
 *
 * @param totalPssBytes         sum of PSS across <i>every</i> smaps VMA - our best estimate
 *                              of the process's real resident memory (PSS, not RSS, to stay
 *                              correct under ZGC's multi-mapping - KT doc S6.4)
 * @param jvmTrackedCategories  resident memory broken down per NMT category (heap, metaspace,
 *                              thread, code cache, GC structures, ...), one entry per category
 *                              that had at least one attributed VMA
 * @param jvmTrackedPssBytes    sum of {@code residentPssBytes} across all of {@code jvmTrackedCategories} -
 *                              i.e. total resident memory the JVM's own accounting explains
 * @param gapEntries            every individual VMA that fell outside all NMT reservations,
 *                              each already fingerprinted into a {@link GapCategory}
 * @param gapTotalsByCategory   {@code gapEntries} pre-aggregated by {@link GapCategory} -> total PSS bytes,
 *                              for quick report rendering without re-scanning the full VMA list
 * @param gapTotalPssBytes      sum of PSS across all {@code gapEntries} - "the gap" itself,
 *                              i.e. resident memory the JVM's own accounting does NOT explain
 * @param cgroupStats           container memory limits/usage, or {@code null} if this process isn't running
 *                              under a detectable cgroup (e.g. no container)
 * @param procStatus            kernel-reported RSS/thread-count cross-check figures
 * @param heapInfo              single-snapshot heap used-vs-committed figures from {@code GC.heap_info}
 * @param nmtThreadCount        thread count as NMT itself reports it (for cross-check against
 *                              {@code procStatus.threads()})
 */
public record ReconciliationResult(
        long totalPssBytes,
        List<CategoryUsage> jvmTrackedCategories,
        long jvmTrackedPssBytes,
        List<GapEntry> gapEntries,
        Map<GapCategory, Long> gapTotalsByCategory,
        long gapTotalPssBytes,
        CgroupStats cgroupStats,
        ProcStatus procStatus,
        HeapInfo heapInfo,
        int nmtThreadCount
) {
}
