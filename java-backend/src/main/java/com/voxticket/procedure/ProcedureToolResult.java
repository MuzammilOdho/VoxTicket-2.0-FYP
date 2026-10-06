package com.voxticket.procedure;

import java.util.Map;

/**
 * Pass 2B: the AI-facing (tool-mediated) representation of a procedure result.
 *
 * <p>This is deliberately separate from {@link ProcedureOutcome}, which stays
 * the domain/runtime result carrying the direct deterministic message for
 * {@code ConversationRuntime}. A {@code ProcedureToolResult} contains only
 * stable codes and whitelisted structured facts - it intentionally has no
 * {@code message}, {@code directMessage}, or {@code customerMessage} field, so
 * the English coordinator prose can never reach the model through this path.
 *
 * <p>{@code details} carries only support-safe facts (order reference, item
 * name, denial reason, quantities, payment consequence, reference numbers);
 * see {@link ProcedureToolResultMapper} for the exact whitelist. It never
 * contains SKUs, database IDs, customer IDs, procedure IDs, verification
 * challenge IDs, OTP material, or raw exception text.
 */
public record ProcedureToolResult(
        boolean success,
        String code,
        String procedure,
        ProcedureNextAction nextAction,
        String orderReference,
        Map<String, Object> details) {

    public ProcedureToolResult {
        details = details == null ? Map.of() : Map.copyOf(details);
    }
}
