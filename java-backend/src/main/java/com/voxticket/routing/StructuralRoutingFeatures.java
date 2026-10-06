package com.voxticket.routing;

/**
 * Phase 2: the deterministic, language-independent facts the structural router sees.
 *
 * <p>Built by {@code StructuralFeatureExtractor} from the current user message and the
 * {@code ConversationSession}. The raw message text itself is NOT a feature - only these
 * derived facts - so structural routing stays cheap and auditable.
 */
public record StructuralRoutingFeatures(
        /** Length of the current user message in characters. */
        int messageLength,
        /** Number of distinct {@code ORD-*} references in the current message. */
        int distinctOrderReferences,
        /** Whether the session currently holds an active (unpaused) procedure. */
        boolean hasActiveProcedure,
        /** Whether the session currently holds a deferred (queued, unauthorized) procedure intent. */
        boolean hasDeferredIntent) {
}
