package com.voxticket.routing.eval;

/** Phase 3: the two held-out corpus splits. Calibration may only see VALIDATION. */
public enum EvalSplit {
    VALIDATION("VAL"),
    FINAL_TEST("TST");

    private final String idPrefix;

    EvalSplit(String idPrefix) {
        this.idPrefix = idPrefix;
    }

    /** The ID prefix used in stable example IDs, e.g. {@code VAL-…} / {@code TST-…}. */
    public String idPrefix() {
        return idPrefix;
    }
}
