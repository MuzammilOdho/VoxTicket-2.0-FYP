package com.voxticket.routing.eval;

import com.voxticket.agent.ModelTier;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Phase 3: corpus loading plus the automatic integrity checks that guard the
 * evaluation against leakage and imbalance.
 *
 * <p>Checks performed by {@link #validate}:
 * <ul>
 *   <li>exact split sizes (120/120), language balance (30 per language per split),
 *       tier balance (15/15 per language per split);</li>
 *   <li>no duplicate IDs; no duplicate normalized text within the corpus;</li>
 *   <li>no normalized-text overlap between VALIDATION and FINAL_TEST;</li>
 *   <li>no exact normalized overlap with the 96 routing prototypes;</li>
 *   <li>no blank text / sourceNote; IDs match their split/language/tier;</li>
 *   <li>every example carries at least one tag, all drawn from {@link #CONTROLLED_TAGS}.</li>
 * </ul>
 * Unknown enum values fail fast at load time (Jackson rejects them).
 */
public final class EvalCorpus {

    /** Controlled tag vocabulary: every example's tags must be drawn from this set. */
    public static final Set<String> CONTROLLED_TAGS = Set.of(
            "ambiguous-reference", "cancellation", "claim-request", "comparison",
            "compound-followup", "conditional", "conflicting-constraints", "correction",
            "delivery-question", "faq", "greeting", "multi-intent", "multi-order",
            "nested-conditional", "order-status", "payment-question", "policy-question",
            "reference-resolution", "return-request", "simple-reference", "single-lookup",
            "synthesis");

    private static final Pattern ID_PATTERN =
            Pattern.compile("^(VAL|TST)-(EN|UR|RU|CS)-(T1|T2)-\\d{3}$");
    private static final int EXPECTED_PER_SPLIT = 120;
    private static final int EXPECTED_PER_LANGUAGE = 30;
    private static final int EXPECTED_PER_TIER = 15;

    private EvalCorpus() {
    }

    /** Loads a corpus JSON array file (fails fast on malformed JSON / unknown enums). */
    public static List<EvalExample> load(Path jsonFile) {
        try {
            return new JsonMapper().readValue(
                    jsonFile.toFile(), new TypeReference<List<EvalExample>>() {
                    });
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Cannot load evaluation corpus from " + jsonFile + ": " + e.getMessage(), e);
        }
    }

    /**
     * Runs every integrity check. Returns the list of violations; empty means clean.
     *
     * @param examples       the combined corpus (both splits)
     * @param prototypeTexts normalized prototype texts from
     *                       {@code src/main/resources/routing/examples-v1.json}
     */
    public static List<String> validate(List<EvalExample> examples, Set<String> prototypeTexts) {
        List<String> violations = new ArrayList<>();

        Map<EvalSplit, List<EvalExample>> bySplit = new EnumMap<>(EvalSplit.class);
        for (EvalSplit split : EvalSplit.values()) {
            bySplit.put(split, new ArrayList<>());
        }
        for (EvalExample example : examples) {
            if (example.split() == null) {
                violations.add("Example has null split: id=" + example.id());
                continue;
            }
            bySplit.get(example.split()).add(example);
        }

        for (EvalSplit split : EvalSplit.values()) {
            List<EvalExample> splitExamples = bySplit.get(split);
            if (splitExamples.size() != EXPECTED_PER_SPLIT) {
                violations.add(split + ": expected " + EXPECTED_PER_SPLIT
                        + " examples, found " + splitExamples.size());
            }
            Map<EvalLanguage, Map<ModelTier, Integer>> counts = new EnumMap<>(EvalLanguage.class);
            for (EvalLanguage language : EvalLanguage.values()) {
                Map<ModelTier, Integer> perTier = new EnumMap<>(ModelTier.class);
                perTier.put(ModelTier.TIER_1, 0);
                perTier.put(ModelTier.TIER_2, 0);
                counts.put(language, perTier);
            }
            for (EvalExample example : splitExamples) {
                if (example.language() != null && example.expectedTier() != null) {
                    Map<ModelTier, Integer> perTier = counts.get(example.language());
                    perTier.put(example.expectedTier(), perTier.get(example.expectedTier()) + 1);
                }
            }
            for (EvalLanguage language : EvalLanguage.values()) {
                int total = counts.get(language).get(ModelTier.TIER_1)
                        + counts.get(language).get(ModelTier.TIER_2);
                if (total != EXPECTED_PER_LANGUAGE) {
                    violations.add(split + "/" + language + ": expected "
                            + EXPECTED_PER_LANGUAGE + " examples, found " + total);
                }
                for (ModelTier tier : ModelTier.values()) {
                    int tierCount = counts.get(language).get(tier);
                    if (tierCount != EXPECTED_PER_TIER) {
                        violations.add(split + "/" + language + "/" + tier + ": expected "
                                + EXPECTED_PER_TIER + " examples, found " + tierCount);
                    }
                }
            }
        }

        Set<String> seenIds = new HashSet<>();
        Map<String, String> textToId = new HashMap<>();
        Map<String, String> validationTexts = new HashMap<>();
        for (EvalExample example : examples) {
            if (!seenIds.add(example.id())) {
                violations.add("Duplicate id: " + example.id());
            }
            if (example.id() == null || !ID_PATTERN.matcher(example.id()).matches()) {
                violations.add("Malformed id: " + example.id());
            } else {
                String[] parts = example.id().split("-");
                boolean idMatches = parts[0].equals(example.split().idPrefix())
                        && parts[1].equals(example.language().idCode())
                        && parts[2].equals(example.expectedTier() == ModelTier.TIER_1 ? "T1" : "T2");
                if (!idMatches) {
                    violations.add("Id does not match split/language/tier: " + example.id());
                }
            }
            if (example.text() == null || example.text().isBlank()) {
                violations.add("Blank text: " + example.id());
            }
            if (example.sourceNote() == null || example.sourceNote().isBlank()) {
                violations.add("Blank sourceNote: " + example.id());
            }
            if (example.tags() == null || example.tags().isEmpty()) {
                violations.add("No tags: " + example.id());
            } else {
                for (String tag : example.tags()) {
                    if (!CONTROLLED_TAGS.contains(tag)) {
                        violations.add("Unknown tag '" + tag + "': " + example.id());
                    }
                }
            }
            String normalized = example.normalizedText();
            if (!normalized.isEmpty()) {
                String firstId = textToId.putIfAbsent(normalized, example.id());
                if (firstId != null) {
                    violations.add("Duplicate normalized text in " + example.id()
                            + " (first seen in " + firstId + ")");
                }
                if (prototypeTexts.contains(normalized)) {
                    violations.add("Evaluation text overlaps a routing prototype: " + example.id());
                }
                if (example.split() == EvalSplit.VALIDATION) {
                    validationTexts.put(normalized, example.id());
                } else if (example.split() == EvalSplit.FINAL_TEST
                        && validationTexts.containsKey(normalized)) {
                    violations.add("FINAL_TEST overlaps VALIDATION text: " + example.id()
                            + " ~ " + validationTexts.get(normalized));
                }
            }
        }
        return violations;
    }
}
