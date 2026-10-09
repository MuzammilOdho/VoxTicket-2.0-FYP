package com.voxticket.safety;

public record NormalizationResult(boolean accepted, String text, Integer rejectedLength) {

    public static NormalizationResult accepted(String text) {
        return new NormalizationResult(true, text, null);
    }

    public static NormalizationResult rejected(int length) {
        return new NormalizationResult(false, null, length);
    }
}