package com.belowdeck.model;

/**
 * One Virtual Memory Area (VMA) parsed from {@code /proc/<pid>/smaps}.
 *
 * <p>Background (KT doc S7.1 - "the gold mine"): the kernel exposes one entry
 * per mapped memory region for a process, each looking like:
 *
 * <pre>
 *     7f2c00000000-7f2c0c000000 rw-p 00000000 00:00 0      [anon: Java Heap]
 *     Size:            1048576 kB
 *     Rss:             524288 kB
 *     Pss:             524288 kB
 *     Private_Dirty:   524288 kB
 *     Anonymous:       524288 kB
 *     ...
 * </pre>
 *
 * <p>This is <b>ground truth</b> for physical residency - unlike NMT
 * (self-reported by the JVM), or cgroup stats (aggregate only), smaps tells you
 * exactly which pages are backed by RAM right now, for every mapping in the process,
 * JVM-known or not. That's what makes it possible to find the "gap" (KT doc Part 10):
 * anything here that isn't inside an NMT reservation is memory that JVM didn't tell you
 * about.
 *
 * <p> Why we track both {@code rssKb} and {@code pssKb}: RSS double-counts shared pages
 * (a page mapped at multiple virtual addresses counts fully at each mapping), while PSS
 * divides shared pages by their mapping count. This matters enormously for ZGC, which
 * deliberately maps the same physical heap pages at multiple virtual addresses
 * ("colored pointers" - KT doc S6.4). If we sum RSS across all VMAs, a ZGC heap would appear
 * to be ~3x its real size. We use PSS everywhere in the reconciler for exactly this reason -
 * see {@code Reconciler} for where this gets applied.
 *
 * @param start             inclusive start virtual address of this mapping
 * @param end               exclusive end virtual address of this mapping
 * @param permissions       raw permission string from the header line, e.g. "rw-p"
 * @param backingPath       the mapping's name/backing file, e.g. "/lib/libc.so.6",
 *                          "[heap]", "[stack]", "[anon: Java heap]", or empty string
 *                          for an anonymous mapping with no label
 * @param rssKb             Resident Set Size in KB - physical pages resident for this
 *                          mapping, counted in full even if shared
 * @param pssKb             Proportional Set Size in KB - resident pages with shared
 *                          pages divided by their mapper count. This is the number the
 *                          reconciler sums for "real" per-process usage.
 * @param privateCleanKb    private pages, not modified since mapped
 * @param privateDirtyKb    private pages, modified - i.e. genuinely this process's
 *                          own data, not reclaimable/shared with anyone
 * @param sharedCleanKb     pages shared with other processes, no modified
 * @param sharedDirtyKb     pages shared with other processes, modified (e.g. COW not yet split)
 * @param anonymousKb       pages not backed by any file (heap, stack, malloc arenas, ...)
 * @param referencedKb      pages marked as recently accessed by the kernels' page reclaim
 *                          bookkeeping - a rough proxy for "actively used" (see KT doc S7.3)
 */
public record SmapsVma(
        long start,
        long end,
        String permissions,
        String backingPath,
        long rssKb,
        long pssKb,
        long privateCleanKb,
        long privateDirtyKb,
        long sharedCleanKb,
        long sharedDirtyKb,
        long anonymousKb,
        long referencedKb
) {
    /** Size of the mapping in bytes, from the address range (not from smaps' own "Size:" field). */
    public long sizeBytes() {
        return end - start;
    }

    /** True if this mapping has no backing file - i.e. it's heap/stack/malloc/mmap-anonymous memory. */
    public boolean isAnonymous() {
        return backingPath == null || backingPath.isEmpty() || backingPath.equals("[anon");
    }
}
