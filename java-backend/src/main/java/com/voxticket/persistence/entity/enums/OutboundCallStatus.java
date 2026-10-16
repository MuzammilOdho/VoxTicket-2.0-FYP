package com.voxticket.persistence.entity.enums;

/**
 * P4.1: lifecycle status of an outbound call row.
 *
 * <p>Java only ever writes {@link #QUEUED} in P4 - the actual dial-out is
 * the Python worker's job, which reports progress through the status
 * endpoint. The remaining values exist so the worker has a place to record
 * what actually happened; they are never fabricated by Java.
 */
public enum OutboundCallStatus {
    /** Persisted by Java; the worker has not picked the call up yet. */
    QUEUED,
    /** The worker is dialing / the call is ringing. */
    DIALING,
    /** The call completed (any terminal outcome, worker-reported). */
    COMPLETED,
    /** The call failed to connect (worker-reported). */
    FAILED
}
