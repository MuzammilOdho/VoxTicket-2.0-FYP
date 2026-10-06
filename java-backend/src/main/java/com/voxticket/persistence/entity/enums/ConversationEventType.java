package com.voxticket.persistence.entity.enums;

public enum ConversationEventType {
    MODEL_SELECTED,
    TOOL_CALLED,
    RAG_SEARCH,
    PROCEDURE_STARTED,
    PROCEDURE_COMPLETED,
    PROCEDURE_FAILED,
    PROCEDURE_CLARIFICATION,
    /** Pass 2D-B: a request was deduplicated against the live procedure or queued as the single deferred intent. */
    PROCEDURE_DEFERRED,
    /**
     * Pass 2D-B cleanup: deferred promotion threw unexpectedly. The completed
     * active mutation is unaffected and the queued intent is preserved; the
     * detail string carries only type and order reference, never exception
     * text, OTP, challenge IDs, hashes, or internal UUIDs.
     */
    DEFERRED_PROMOTION_FAILED,
    OTP_ISSUED,
    OTP_VERIFIED,
    OTP_FAILED,
    EXECUTION_SUCCEEDED,
    EXECUTION_FAILED,
    ESCALATED,
    SAFETY_BLOCKED,
    SUSPECTED_FABRICATION
}
