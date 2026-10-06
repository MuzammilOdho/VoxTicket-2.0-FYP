package com.voxticket.routing.eval;

/**
 * Phase 3: evaluation corpus language categories. Language itself must never be a
 * complexity signal - every language carries the same 15/15 tier balance per split.
 */
public enum EvalLanguage {
    ENGLISH("EN"),
    URDU("UR"),
    ROMAN_URDU("RU"),
    CODE_SWITCH("CS");

    private final String idCode;

    EvalLanguage(String idCode) {
        this.idCode = idCode;
    }

    /** The two-letter code used in stable example IDs, e.g. {@code VAL-EN-T1-001}. */
    public String idCode() {
        return idCode;
    }
}
