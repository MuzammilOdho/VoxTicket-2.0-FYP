package com.voxticket.procedure;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pass 2B: the single centralized translation from the coordinator/runtime
 * {@link ProcedureOutcome} to the model-facing {@link ProcedureToolResult}.
 *
 * <p>Guarantees:
 * <ul>
 *   <li>never reads or parses {@code ProcedureOutcome.message()} - every fact
 *       comes from stable codes or whitelisted metadata keys;</li>
 *   <li>only whitelisted support-safe metadata reaches the model; everything
 *       else (SKU, database IDs, customer IDs, procedure IDs, verification
 *       challenge IDs, OTP material, internal slot keys, raw exceptions,
 *       provider details) is dropped;</li>
 *   <li>the {@code candidateItem.N} indexed metadata keys the coordinator
 *       attaches for ambiguous item resolution are folded into a single
 *       {@code candidateItems} list of customer-visible product names;</li>
 *   <li>no business state is inferred from English prose - the outcome code
 *       and the metadata are the only inputs.</li>
 * </ul>
 */
public final class ProcedureToolResultMapper {

    /** Procedure label for the model-mediated human-support tool. */
    public static final String HUMAN_SUPPORT = "HUMAN_SUPPORT";

    /**
     * The only {@link ProcedureOutcome} metadata keys allowed into the
     * model-facing payload. All keys are attached by the coordinator at the
     * source (Pass 2B Task 4) and contain only customer-visible values.
     *
     * <p>{@code orderReference} is intentionally NOT in this set: it is
     * promoted to the top-level {@code orderReference} field of
     * {@link ProcedureToolResult} and must never be duplicated inside
     * {@code details}. {@code maxReturnableQuantity} is also excluded here -
     * it is typed as a JSON number by {@link #typedMaxReturnableQuantity}
     * instead of being copied as a string.
     */
    private static final Set<String> SAFE_METADATA_KEYS = Set.of(
            "itemName",
            "denialReason",
            "paymentConsequence",
            "returnNumber",
            "returnReason",
            "claimNumber",
            "claimReason",
            "problemDescription",
            "ticketNumber");

    private ProcedureToolResultMapper() {
    }

    /**
     * Converts a coordinator outcome into the model-facing result.
     *
     * @param procedure the model-facing procedure label - one of
     *                  {@code ProcedureType.name()} or {@link #HUMAN_SUPPORT}
     * @param outcome   the coordinator/runtime outcome; its {@code message()}
     *                  is deliberately never copied or parsed
     */
    public static ProcedureToolResult toToolResult(String procedure, ProcedureOutcome outcome) {
        Map<String, String> metadata = outcome.metadata() == null ? Map.of() : outcome.metadata();
        Map<String, Object> details = new LinkedHashMap<>();
        for (String key : SAFE_METADATA_KEYS) {
            String value = metadata.get(key);
            if (value != null) {
                details.put(key, value);
            }
        }
        List<String> candidates = indexedCandidateItems(metadata);
        if (!candidates.isEmpty()) {
            details.put("candidateItems", List.copyOf(candidates));
        }
        typedMaxReturnableQuantity(metadata, details);
        return new ProcedureToolResult(
                outcome.success(),
                outcome.code(),
                procedure,
                ProcedureNextAction.fromOutcomeCode(outcome.code()),
                metadata.get("orderReference"),
                details);
    }

    /**
     * Copies {@code maxReturnableQuantity} into the model-facing payload as a
     * JSON number rather than the string form that
     * {@code Map<String, String>} metadata carries. Malformed internal
     * metadata is omitted instead of being emitted as invalid data.
     */
    private static void typedMaxReturnableQuantity(Map<String, String> metadata, Map<String, Object> details) {
        String maxQuantity = metadata.get("maxReturnableQuantity");
        if (maxQuantity == null) {
            return;
        }
        try {
            details.put("maxReturnableQuantity", Integer.parseInt(maxQuantity));
        } catch (NumberFormatException ignored) {
            // Omit malformed internal metadata rather than exposing an invalid value.
        }
    }

    /**
     * Folds the indexed {@code candidateItem.1}, {@code candidateItem.2}, ...
     * metadata keys into an ordered list. Stops at the first gap; only
     * customer-visible product names are ever stored under these keys.
     */
    private static List<String> indexedCandidateItems(Map<String, String> metadata) {
        List<String> candidates = new ArrayList<>();
        for (int i = 1; ; i++) {
            String candidate = metadata.get("candidateItem." + i);
            if (candidate == null) {
                break;
            }
            candidates.add(candidate);
        }
        return candidates;
    }
}
