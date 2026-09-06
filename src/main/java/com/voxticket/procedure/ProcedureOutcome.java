package com.voxticket.procedure;

public record ProcedureOutcome(boolean success, String code, String message) {

    public static ProcedureOutcome ok(String code, String message) {
        return new ProcedureOutcome(true, code, message);
    }

    public static ProcedureOutcome error(String code, String message) {
        return new ProcedureOutcome(false, code, message);
    }
}