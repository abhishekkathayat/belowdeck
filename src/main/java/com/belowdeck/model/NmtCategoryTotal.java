package com.belowdeck.model;

/**
 * The reserved/committed totals for one NMT category, as printed in the
 * "summary" style block at the top of {@code jcmd <pid> VM.native_memory detail}
 * output, e.g.:
 *
 * <pre>
 *     Java Heap (reserved=8388608KB, committed=1048576KB)
 * </pre>
 *
 * <p> This is distinct from {@link NmtRegion}: {@code NmtRegion} gives you the
 * per-reservation <i>addresses</i> (used for the interval-tree join against smaps),
 * whereas this record gives you the <i>grand totals per category</i> as the JVM
 * itself understands them - handy to print next to the resident-memory figures
 * we compute ourselves, so the report can show both "what the JVM thinks it committed"
 * and "what's actually resident" side by side (see KT doc S11.3 - "you reconcile
 * NMT's category boundaries against smap's resident numbers, never NMT-committed directly
 * against RSS").
 *
 * @param category          NMT category name, verbatim from JVM output
 * @param reservedBytes     virtual address space claimed for this category
 * @param committedBytes    pages the JVM has backed and is willing to use
 *                          (NOT the same as "resident" - see KT doc S6.3)
 */

public record NmtCategoryTotal(String category, long reservedBytes, long committedBytes) {
}
