package com.voxticket.procedure;

/** Spec §13/§14: at most one active + one paused procedure per session. */
public enum ProcedureSlotResult {
    STARTED,
    STARTED_AND_PAUSED_PREVIOUS,
    BOTH_SLOTS_OCCUPIED
}