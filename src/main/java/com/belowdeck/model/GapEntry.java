package com.belowdeck.model;

/**
 * One smaps VMA that landed in "the gap" (not covered by any NMT reservation),
 * together with the classification the {@code GapClassifier}
 * (and the {@code Reconciler}'s cluster-confirmation pass) assigned to it.
 *
 * @param vma           the original smaps mapping
 * @param category      the fingerprint bucket this VMA was sorted into
 * @param note          a short, human-readable explanation of "why" it was classified
 *                      this way - shown in verbose report output so the classification
 *                      isn't a black box. May be {@code null} if the category is self-explanatory
 *                      (e.g. a plain "*.so" path).
 */
public record GapEntry(SmapsVma vma, GapCategory category, String note) {

    public GapEntry withCategory(GapCategory newCategory, String newNote) {
        return new GapEntry(vma, newCategory, newNote);
    }
}
