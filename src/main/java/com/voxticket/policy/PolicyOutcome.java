package com.voxticket.policy;

/** Spec §19. */
public enum PolicyOutcome {
    ALLOW,
    DENY,
    REQUIRE_VERIFICATION,
    REQUIRE_CONFIRMATION,
    ESCALATE
}