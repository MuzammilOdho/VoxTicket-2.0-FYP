package com.voxticket.persistence.entity.enums;

/**
 * P4.1: why an outbound call was triggered.
 *
 * <p>The set is intentionally closed and small: P4 supports API-triggered
 * single calls only. There is no campaign concept, no templating, and no
 * scheduling - a trigger names one of these reasons and the worker dials
 * exactly one call.
 */
public enum OutboundReason {
    /** A delivery status update the customer asked to be told about. */
    DELIVERY_UPDATE,
    /** A reminder for an upcoming appointment. */
    APPOINTMENT_REMINDER,
    /** A human follow-up after a previous interaction. */
    FOLLOW_UP,
    /** A reminder about a pending payment. */
    PAYMENT_REMINDER,
    /** A request for quick feedback on a recent experience. */
    FEEDBACK_REQUEST
}
