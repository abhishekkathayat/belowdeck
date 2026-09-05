package com.belowdeck.model;

import java.util.List;
import java.util.Map;

/**
 * Everything the {@code NMTCollector} extracts from one
 * {@code jcmd <pid> VM.native_memory detail} invocation, bundled together.
 *
 * <p>Two shapes of data come out of a single piece of JVM output:
 * <ul>
 *     <li>{@link #regions()} - the per-reservation address ranges tagged by
 *          category. This is the input to the interval tree that the
 *          {@code Reconciler} builds (KT doc S12.2 step 1).</li>
 *     <li>{@link #categoryTotals()} - the reserved/committed grand totals per
 *          category, as the JVM reports them in the summary block. Used purely
 *          for display/comparison in the report, not for the join itself.</li>
 * </ul>
 *
 * <p>{@link #threadCount()} is pulled from the "Thread" category's {@code (thread #N)}
 * line and is useful on its own: a runway thread count is one of the most common, and
 * most invisible-to-heap-tools, causes of native memory bloat (KT doc S4.4 -
 * "2,000 leaked threads x 1 MB = 2 GB of RSS that no heap dump explains").
 *
 * @param regions           address-range reservations, used for the interval-tree join
 * @param categoryTotals    reserved/committed totals per NMT category, for display
 * @param threadCount       number of live JVM threads at collecton time, or -1 if it could
 *                          not be parsed out of the NMT output
 */

public record NmtSnapshot(
        List<NmtRegion> regions,
        Map<String, NmtCategoryTotal> categoryTotals,
        int threadCount) {
}
