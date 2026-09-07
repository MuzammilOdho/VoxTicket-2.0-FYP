package com.voxticket.conversation;

/** Spec §15. */
public enum RecentActionType {
    ORDER_CANCELLED,
    REFUND_INITIATED,
    REFUND_SUCCEEDED,
    REFUND_FAILED,
    RETURN_REQUESTED,
    RETURN_COMPLETED,
    CLAIM_FILED,
    CLAIM_RESOLVED,
    ESCALATED
}