package com.belowdeck.reconciler;

import com.belowdeck.model.GapCategory;
import com.belowdeck.model.SmapsVma;

/**
 * Fingerprints a single smaps VMA that fell outside every NMT reservation,
 * assigning it a first-pass {@link GapCategory} based purely on its own
 * signature (backing path, permissions, size). This implements the
 * classification table from KT doc S12.3:
 *
 * <pre>
 *     Signature                                                Classification
 *     -------------------------------------------------------  --------------
 *     File-backed, name ends .so                               Native library
 *     [heap] / brk region                                      C heap
 *     Anonymous, rw-p, size = 64MB or multiples, clustered     glibc malloc arena
 *     [stack] or thread-stack-sized anon not in NMT            stray thread stack
 *     File-backed, data file mmap                              mmap'd file
 *     Anything else                                            Unknown
 * </pre>
 *
 * <h2>Why "clustered" isn't decided here</h2>
 * Whether a 64&nbsp;MB anonymous mapping is really part of a glibc arena cluster
 * - as opposed to one unrelated large {@code malloc}/{@code mmap} call that happens
 * to be a page-aligned to a round number - can only be judged by looking at *all*
 * tha gap VMAs together (how many such mappings exist across the whole process).
 * A single VMA can't answer that on its own. So this class only emits the provisional
 * {@link GapCategory#ARENA_CANDIDATE} label;
 * {@code Reconciler} does the second pass promotes candidates to
 * {@link GapCategory#GLIBC_MALLOC_ARENA} (if enough of them exist) or demotes lone
 * candidates to {@link GapCategory#UNKNOWN}.
 */

public final class GapClassifier {

    /** glibc grows its per-thread mmap'd areas in 64MB chunks (KT doc S10.1). */
    static final long ARENA_CHUNK_BYTES = 64L * 1024 * 1024;

    private GapClassifier() {}

    public static GapCategory classify(SmapsVma vma) {
        String path = vma.backingPath();

        if (path != null && path.contains(".so")) {
            return GapCategory.NATIVE_LIBRARY;
        }
        if ("[heap]".equals(path)) {
            return GapCategory.C_HEAP;
        }
        if (path != null && path.startsWith("[stack")) {
            return GapCategory.THREAD_STACK;
        }
        boolean writeablePrivateAnonymous = vma.isAnonymous() && vma.permissions().startsWith("rw");
        long sizeBytes = vma.sizeBytes();
        if (writeablePrivateAnonymous && sizeBytes > 0 && sizeBytes % ARENA_CHUNK_BYTES == 0) {
            return GapCategory.ARENA_CANDIDATE;
        }
        boolean isRealFile = path != null && !path.isBlank() && !path.startsWith("[");
        if (isRealFile) {
            return GapCategory.MAPPED_FILE;
        }
        return GapCategory.UNKNOWN;
    }
}