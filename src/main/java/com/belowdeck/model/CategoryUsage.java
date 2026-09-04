package com.belowdeck.model;

/**
 * The reconciled, resident-memory picture for one NMT category - i.e. the
 * answer to "of everything the JVM told us it reserved for category X, how
 * much of it is actually sitting in physical RAM right now?
 *
 * <p> This is the key output of the interval-tree join (KT doc S12.2 step 3):
 * for every smaps VMA whose start address falls inside an NMT reservation, we
 * attribute that VMA's <b>PSS</b> (not RSS - see {@link SmapsVma} for why to
 * the owning category. Summed across all VMAs in a category, that gives you
 * resident memory per JVM subsystem - which the KT doc calls out as "strictly
 * better than NMT's committed numbers, because it's what's actually in RAM"
 *
 * @param category          NMT category name, verbatim (e.g. "Java Heap", "Class", "Thread")
 * @param residentPssBytes  sum of PSS across every smaps VMA attributed to this category -
 *                          the reconciled "actually resident" figure
 * @param vmaCount          how many distinct smaps VMAs were attributed here (e.g. thread
 *                          stack count roughly tracks live thread count)
 * @param privateDirtyBytes sum of Private_Dirty acrss attributed VMAs - genuinely
 *                          this-process-ownedn non-shared, non-reclaimable memory
 * @param nmtReservedBytes  what NMT itself says it reserved for this category (0 if
 *                          category total wasn't found in the NMT summary block)
 * @param nmtCommittedBytes what NMT itself says it committed for this categor (0 if
 *                          no found) - compare against {@code residentPssBytes} to see
 *                          committed but-not-yet-touched vs actually-resident memory
 */

public record CategoryUsage(
        String category,
        long residentPssBytes,
        int vmaCount,
        long privateDirtyBytes,
        long nmtReservedBytes,
        long nmtCommittedBytes
) {
}
