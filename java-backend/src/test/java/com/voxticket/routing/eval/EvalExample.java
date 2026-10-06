package com.voxticket.routing.eval;

import com.voxticket.agent.ModelTier;
import java.util.List;
import java.util.Locale;

/**
 * Phase 3: one held-out evaluation example. Labels are deterministic research labels
 * based on the complexity rubric in {@code evaluation/routing/corpus/RUBRIC.md} -
 * conversational/reasoning complexity, never security sensitivity or business risk.
 */
public record EvalExample(
        String id,
        EvalSplit split,
        EvalLanguage language,
        ModelTier expectedTier,
        String text,
        List<String> tags,
        String sourceNote) {

    /** Canonical form used for duplicate / leakage detection. */
    public String normalizedText() {
        if (text == null) {
            return "";
        }
        return text.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    /** The ID this example is expected to carry, e.g. {@code VAL-EN-T1-001}. */
    public String expectedId(String sequence) {
        return split.idPrefix() + "-"
                + language.idCode() + "-"
                + (expectedTier == ModelTier.TIER_1 ? "T1" : "T2") + "-"
                + sequence;
    }
}
