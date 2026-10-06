package com.voxticket.routing;

/**
 * Phase 2: deterministic structural complexity signals.
 *
 * <p>These are cheap, language-independent facts about the message and the session - never a model
 * call, never business risk, never authorization state. Business risk (mutation/OTP involvement)
 * is deliberately NOT a signal: "Cancel ORD-10010" is a perfectly good Tier 1 request.
 */
public enum StructuralRoutingSignal {
    /** Two or more distinct {@code ORD-*} references in the current message. */
    MULTI_ORDER_REFERENCE,
    /** Message length at or above the configured long-message threshold. */
    LONG_COMPOUND_MESSAGE,
    /** Active procedure plus paused procedure plus a new substantive request. */
    COMPLEX_SESSION_STATE
}
