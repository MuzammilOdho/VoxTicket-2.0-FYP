package com.voxticket.conversation;

/** Spec §15. Populated starting in Phase 8, once guarded procedures actually invoke the Phase 3 services these map to. */
public enum RecentActionType {
    ORDER_CANCELLED,
    REFUND_INITIATED,
    REFUND_SUCCEEDED,
    REFUND_FAILED,
    RETURN_REQUESTED,
    RETURN_COMPLETED,
    CLAIM_FILED,
    CLAIM_RESOLVED
}