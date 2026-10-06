package com.voxticket.verification;

/**
 * Pass 2C (Task 4): stable internal codes for every deterministic
 * {@link VerificationService#verify} branch. The direct-response renderer keys
 * off this code instead of parsing the English compatibility message, so
 * multilingual presentation stays exact even as prose changes.
 */
public enum VerificationResultCode {
    /** The submitted code verified against the pending challenge. */
    VERIFIED,
    /** No verification challenge is pending for this session. */
    NO_PENDING,
    /** The pending challenge record is missing or already consumed. */
    NO_LONGER_VALID,
    /** The challenge is not bound to the procedure/order being verified. */
    BINDING_MISMATCH,
    /** The challenge has expired. */
    EXPIRED,
    /** The submitted code did not match (attempts remain). */
    WRONG_CODE,
    /** Maximum failed attempts reached - the challenge is no longer usable. */
    MAX_ATTEMPTS_EXCEEDED
}
