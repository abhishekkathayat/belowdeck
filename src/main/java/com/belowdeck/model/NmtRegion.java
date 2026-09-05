package com.belowdeck.model;

/**
 * A single address-range entry from {@code jcmd <pid> VM.native_memory details}'s
 * "Virtual memory map" section.
 *
 * <p>Background (see KT doc, Part 11 "Native Memory Tracking (NMT)) in depth"):
 * NMT is the JVM's own bookkeeping of the native memory it reserves/commits on
 * your behalf - Java Heap, Metaspace, thread stacks, code cache, GC structures,
 * etc. When you run NMT with {@code detail} (not just {@code summary}), the JVM
 * additionally prints the actual virtual address range it reserved for each
 * category, e.g.:
 *
 * <pre>
 *     [0x00007f2c00000000 - 0x00007f2c40000000] reserved 1073741824 for Java Heap
 *     [0x00007f2c00000000 - 0x00007f2c30000000] committed 805306368
 * </pre>
 *
 * <p>This class models exactly the <b>outer "reserved ... for CATEGORY"</b> line -
 * i.e. the big virtual-memory reservation, not the finer-grained "committed"
 * sub-range beneath it. That's a deliberate scope decision: the KT doc's
 * reconciliation algorithm (S12.2) joins smaps VMAs against NMT <i>reservations</i>
 * ("each NMT reservation contains many smaps VMAs"), so the reservation is the right
 * granularity for the interval-tree containment check. The committed sub-ranges give
 * finer detail but aren't needed for attributing a smaps VMA to "who owns this address"
 * - the reservation boundary already answers that.
 *
 * <p>Addresses are only meaningful <i>within a single collection run</i> - never
 * cache/compare them across separate JVM runs or restarts (ASLR / address-space layout
 * can differ every launch). See KT doc S12.2 edge cases.
 *
 * @param baseAddress   inclusive start of the reserved virtual address range
 * @param endAddress    exclusive end of the reserved virtual address range
 * @param category      the NMT category name exactly as HotSpot prints it,
 *                      e.g. "Java Heap", "Class", "Thread", "Code", "GC", "Internal",
 *                      "Symbol", "Metaspace", "Other". We keep the JVM's own spelling
 *                      rather than inventing our own enum so the report can always
 *                      show precisely what NMT said.
 */

public record NmtRegion(long baseAddress, long endAddress, String category) {

    public NmtRegion {
        if (endAddress <= baseAddress) {
            throw new IllegalArgumentException(
                    "NMT region end (" + Long.toHexString(endAddress) +
                            ") must be greater than base (" + Long.toHexString(baseAddress) + ")");
        }
    }

    /** Size of this reservation in bytes - handy for report printing and sanity checks. */
    public long sizeBytes() {
        return endAddress - baseAddress;
    }
}
