package com.belowdeck.model;

/**
 * A minimal snapshot of Java heap sizing from {@code jcmd <pid> GC.heap_info}.
 *
 * <p>Why this exists, given NMT already reports a "Java Heap" category:
 * NMT's "Java Heap" numbers are {@code reserved} and {@code committed} -
 * virtual address space claimed and OS-backed pages, respectively (KT doc S6.3).
 * Neither of those tells you how much of the heap is actually holding
 * <i>live objects</i> right now. {@code GC.heap_info} gives collector-reported
 * {@code used} vs {@code total} (committed) figures, which lets the report answer
 * a very common and confusing question:
 *
 * <blockquote>"My RSS is high but my heap `used` is low - do I have a leak?"</blockquote>
 *
 * <p>Usually the answer is no - it's committed-but-not-yet-returned-to-the-OS heap
 * space (KT doc S6.3 "Practical takeaway"). This class exists purely to let the heuristic
 * engine make that distinction on a single snapshot, without needing a second, later
 * snapshot to compare against (true leak detection via trend-over-time is explicitly
 * out of scope for this tool - see KT doc S12.8 Phase 3, which this project does not implement).
 *
 * <p>Parsing note: {@code GC.heap_info} output format differs per collector
 * (G1, Parallel, ZGC, Shenandoah, Serial all print slightly different text).
 * The collector for this class is intentionally best-effort: if it can't confidently
 * find both a total and a used figure, it reports {@link #available()} as {@code false}
 * rather than guessing.
 *
 * @param totalBytes    committed heap size as reported by the collector, in bytes
 * @param usedBytes     live-object bytes as reported by the collector, at time of snapshot
 * @param rawOutput     the raw {@code GC.heap_info} text, kept for the report to show
 *                      verbatim if parsing failed or for extra operator context
 * @param available     whether totalBytes/usedBytes were confidently parsed
 */

public record HeapInfo(long totalBytes, long usedBytes, String rawOutput, boolean available) {

    public static HeapInfo unavailable(String rawOutput) {
        return new HeapInfo(0, 0, rawOutput, false);
    }

    /** Fraction of commited heap actually holding live objects, in [0, 1].
     * Only meaningful if {@link #available()}
     */
    public double usedFraction() {
        return totalBytes == 0 ? 0.0 : (double) usedBytes / (double) totalBytes;
    }
}
