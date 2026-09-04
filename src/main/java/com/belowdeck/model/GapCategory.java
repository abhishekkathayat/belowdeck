package com.belowdeck.model;

/**
 * The subclassification bucket for a smaps VMA that falls <b>outside</b>
 * every NMT reservation - i.e. a piece of resident memory that JVM's own
 * accounting doesn't explain. This is "the gap" (KT doc Part 10 and S12.3).
 *
 * <p>Every VMA that isn't contained in an NMT-reserved address range gets
 * fingerprinted into exactly one of these buckets based on its smaps
 * signature (backing file name, permissions, size, clustering with similar VMAs).
 * See {@code GapClassifier} for the fingerprinting rules and {@code Reconciler}
 * for the two-pass logic that promotes/demotes {@link #ARENA_CANDIDATE}.
 */
public enum GapCategory {

    /**
     * A file-backed mapping whose path looks like a shared library
     * (contains ".so"). Usually cheap in PSS terms because the underlying
     * pages are shared across every process that loaded the same library -
     * check {@code Private_Dirty} to see if this instance actually holds
     * private copies (e.g. due to relocations/copy-on-write). KT doc S10.3.
     */
    NATIVE_LIBRARY,

    /**
     * The traditional C heap - the `[heap]` / brk-managed small allocation
     * arena used by glibc malloc for small requests that don't warrant their own
     * mmap. Distinct from glibc's per-thread mmap'd arenas (see {@link #GLIBC_MALLOC_ARENA}).
     * KT doc S2.3, S10.1.
     */
    C_HEAP,

    /**
     * Anonymous, writable, 64&nbsp;MB-aligned mappings appearing in a cluster -
     * the fingerprint of glibc's per-thread malloc arenas (KT doc S10.1).
     * This is deliberately a two-stage classification: a single 64&nbsp;MB
     * anonymous mapping isn't strong evidence of its own (it could just be a
     * large one-off allocation); only when appear together does the reconciler
     * confirm this bucket. See {@link #ARENA_CANDIDATE}.
     */
    GLIBC_MALLOC_ARENA,

    /**
     * Internal, provisional label used only during classification: an
     * anonymous, writable, 64&nbsp;MB-aligned mapping that has <i>not yet</i>
     * been confirmed as part of an arena cluster. The reconciler either promotes
     * all candidates to {@link #GLIBC_MALLOC_ARENA} (if enough of them exist)
     * or demotes them individually to {@link #UNKNOWN}. This value should never
     * appear in a final {@code ReconiliationResult}.
     */
    ARENA_CANDIDATE,

    /**
     * A thread stack ({@code [stack]} or {@code [stack:tid]} in smaps) that
     * NMT's own "Thread" reservation didn't cover. Ordinarily NMT tracks every
     * JVM thread stack it creates, so a stray, untracked stack usually means a native
     * thread created outside the JVM's own thread-creation path
     * (e.g. directly via pthread from JNI code). KT doc S4.4.
     */
    THREAD_STACK,

    /**
     * A file-backed mapping that isn't a shared library - e.g. a memory mapped
     * data file, JAR, or class file. Often largely reclaimable by the kernel under
     * pressure (it can just re-read the file later). KT doc S10.3.
     */
    MAPPED_FILE,

    /**
     * Didn't match any recognized fingerprint. Reported honestly rather than forced
     * into a bucket that doesn't fit - per KIT doc S12.3: "report honestly, don't force
     * a bucket."
     */
    UNKNOWN
}
