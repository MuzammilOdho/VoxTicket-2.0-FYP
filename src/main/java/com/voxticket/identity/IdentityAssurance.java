package com.voxticket.identity;

/**
 * Spec §8. Ordered from weakest to strongest - IdentityAssurance.ordinal()
 * is used for "at least" comparisons (see CustomerIdentity.isAtLeast), so
 * the declaration order below is load-bearing. Do not reorder without
 * checking every isAtLeast(...) call site.
 */
public enum IdentityAssurance {
    ANONYMOUS,
    PHONE_MATCHED,
    OTP_VERIFIED
}