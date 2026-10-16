package com.voxticket.conversation;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * P2.1: the known dialogue-slot vocabulary.
 *
 * <p>Slot names are a closed set derived from what the procedures actually
 * use (order reference, item reference, problem text, return reason,
 * quantity) plus the ticket reference the read tools resolve. The LLM may
 * only propose slots from this vocabulary - {@link DialogueStateUpdater}
 * rejects anything else and logs it.
 *
 * <p>Each entry carries a value-format rule so Java - not the model -
 * decides whether a proposed value is well-formed.
 */
public final class DialogueSlotNames {

    public static final String ORDER_REFERENCE = "order_reference";
    public static final String TICKET_REFERENCE = "ticket_reference";
    public static final String ITEM_REFERENCE = "item_reference";
    public static final String PROBLEM_DESCRIPTION = "problem_description";
    public static final String RETURN_REASON = "return_reason";
    public static final String RETURN_QUANTITY = "return_quantity";

    private static final Pattern ORDER_PATTERN = Pattern.compile("^[A-Za-z]{2,4}-?\\d{3,}$");
    private static final Pattern TICKET_PATTERN = Pattern.compile("^[Tt][Cc][Kk]-?\\d{3,}$");
    private static final Pattern QUANTITY_PATTERN = Pattern.compile("^\\d{1,3}(\\.\\d{1,2})?$");
    private static final int MAX_FREE_TEXT = 500;
    private static final int MAX_ITEM_TEXT = 120;

    /** Customer-facing description per slot, used in clarification copy. */
    private static final Map<String, String> DESCRIPTIONS = Map.of(
            ORDER_REFERENCE, "order number",
            TICKET_REFERENCE, "ticket number",
            ITEM_REFERENCE, "item",
            PROBLEM_DESCRIPTION, "problem description",
            RETURN_REASON, "return reason",
            RETURN_QUANTITY, "return quantity");

    /** Slots whose value directly targets a commerce mutation. */
    private static final Set<String> MUTATION_TARGETS = Set.of(ORDER_REFERENCE, ITEM_REFERENCE);

    private DialogueSlotNames() {
    }

    /** True when the name is in the known vocabulary. */
    public static boolean isKnown(String name) {
        return DESCRIPTIONS.containsKey(name);
    }

    /** Customer-facing description, e.g. "order number". Unknown names echo back. */
    public static String describe(String name) {
        return DESCRIPTIONS.getOrDefault(name, name);
    }

    /** True when the slot's value can target a commerce mutation (P2.4 policy applies). */
    public static boolean isMutationTarget(String name) {
        return MUTATION_TARGETS.contains(name);
    }

    /**
     * Validates a proposed value's format. Returns empty when the value is
     * well-formed, otherwise a short machine-readable reason
     * ({@code "blank"}, {@code "bad_format"}, {@code "too_long"},
     * {@code "unknown_reason"}).
     */
    public static Optional<String> formatProblem(String name, String value) {
        if (value == null || value.isBlank()) {
            return Optional.of("blank");
        }
        String trimmed = value.trim();
        return switch (name) {
            case ORDER_REFERENCE -> ORDER_PATTERN.matcher(trimmed).matches()
                    ? Optional.empty() : Optional.of("bad_format");
            case TICKET_REFERENCE -> TICKET_PATTERN.matcher(trimmed).matches()
                    ? Optional.empty() : Optional.of("bad_format");
            case ITEM_REFERENCE -> trimmed.length() > MAX_ITEM_TEXT
                    ? Optional.of("too_long") : Optional.empty();
            case PROBLEM_DESCRIPTION -> trimmed.length() > MAX_FREE_TEXT
                    ? Optional.of("too_long") : Optional.empty();
            case RETURN_REASON -> isKnownReturnReason(trimmed)
                    ? Optional.empty() : Optional.of("unknown_reason");
            case RETURN_QUANTITY -> QUANTITY_PATTERN.matcher(trimmed).matches()
                    ? Optional.empty() : Optional.of("bad_format");
            default -> Optional.of("unknown_slot");
        };
    }

    private static boolean isKnownReturnReason(String value) {
        try {
            com.voxticket.persistence.entity.enums.ReturnReason
                    .valueOf(value.trim().toUpperCase(Locale.ROOT));
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
