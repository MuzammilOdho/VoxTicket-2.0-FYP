package com.voxticket.procedure;

import java.util.Map;

public record ProcedureOutcome(boolean success, String code, String message, Map<String, String> metadata) {

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