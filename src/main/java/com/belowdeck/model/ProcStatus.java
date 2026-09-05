package com.belowdeck.model;

/**
 * A handful of fields pulled from {@code /proc/<pid>/status} - the kernel's
 * own quick-look summary for a process.
 *
 * <p>This exists mainly as a <b>cross-check</b>, not as a primary data source.
 * The reconciler's real ground truth is smaps (per-VMA, summable into an accurate
 * PSS total). {@code VmRSS} here is the kernel's own pre-computed RSS figure for
 * the whole process - if it disagrees wildly without summed-from-smaps RSS, that's
 * a signal something is wrong with the collection (e.g. smaps was read while the process
 * was actively remapping memory, a rare race condition), not that the JVM's memory
 * model itself is broken. See KT doc S7.1 and the cheat sheet in Part 13.
 *
 * <p>{@code threads} is also cross-referenced against the thread count NMT reports, since
 * a mismatch there can indicate native (non-JVM) threads doing their own allocations
 * outside JVM tracking entirely.
 *
 * @param vmRssKb   kernel-reported resident set size, in KB
 * @param vmSizeKb  kernel-reported total virtual memory size, in KB
 * @param threads   number of threads (kernel tasks) belonging to this process
 */

public record ProcStatus(long vmRssKb, long vmSizeKb, int threads) {
}
