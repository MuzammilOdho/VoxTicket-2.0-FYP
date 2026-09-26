package com.voxticket.procedure;

import java.util.Map;
import java.util.Set;

public record ProcedureOutcome(boolean success, String code, String message, Map<String, String> metadata) {

    /**
     * Outcome codes that ask the customer for missing information - clarification
     * states, not genuine failures. Shared by the audit-event classifier
     * (ProcedureCoordinator) and the dashboard's clarification metrics
     * (AdminDashboardService); the metric recording itself is unchanged so
     * existing dashboard counts are preserved.
     */
    public static final Set<String> CLARIFICATION_CODES = Set.of("ITEM_REQUIRED", "REASON_REQUIRED", "PROBLEM_REQUIRED");

    public static ProcedureOutcome ok(String code, String message) {
        return new ProcedureOutcome(true, code, message, Map.of());
    }

    public static ProcedureOutcome ok(String code, String message, Map<String, String> metadata) {
        return new ProcedureOutcome(true, code, message, metadata);
    }

    public static ProcedureOutcome error(String code, String message) {
        return new ProcedureOutcome(false, code, message, Map.of());
    }
}
