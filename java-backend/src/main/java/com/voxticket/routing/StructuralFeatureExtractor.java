package com.voxticket.routing;

import com.voxticket.conversation.ConversationSession;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Phase 2: deterministic, language-independent structural feature extraction.
 *
 * <p>Sees only derived facts - message length, distinct {@code ORD-*} references, session
 * procedure slots - never business risk, authorization state, OTP involvement, or provider
 * fallback. "Cancel ORD-10010" is a perfectly good Tier 1 request; business risk is
 * deliberately NOT a routing signal.
 *
 * <p>The English-only conditional-marker heuristic from Phase 1 ("unless", "if", ...) is
 * intentionally gone: it was English-only and added nothing the semantic tier cannot do
 * better across languages.
 */
@Component
public class StructuralFeatureExtractor {

    private static final Pattern ORDER_REFERENCE = Pattern.compile("\\bORD-\\w+\\b", Pattern.CASE_INSENSITIVE);
    /**
     * A message shorter than this while two procedures are open is treated as an ack or
     * clarification fragment, not a new substantive request.
     */
    private static final int SUBSTANTIVE_MESSAGE_MIN_LENGTH = 20;

    private final StructuralRoutingProperties properties;

    /**
     * Takes the bound {@link RoutingProperties} bean (the only selector properties type
     * registered with Spring) and reads the structural subtree from it. The nested
     * {@link StructuralRoutingProperties} record is NOT a bean and must never be injected
     * directly - doing so breaks every full application context with an unsatisfied
     * dependency.
     */
    public StructuralFeatureExtractor(RoutingProperties routingProperties) {
        this.properties = routingProperties.structural();
    }

    public StructuralRoutingFeatures extract(ConversationSession session, String userMessage) {
        String text = userMessage == null ? "" : userMessage;
        int distinctOrders = countDistinctOrderReferences(text);
        boolean hasActive = session.getActiveProcedure().isPresent();
        boolean hasDeferred = session.getDeferredIntent().isPresent();
        return new StructuralRoutingFeatures(text.length(), distinctOrders, hasActive, hasDeferred);
    }

    /** True when the multi-order signal fires for the given features. */
    public boolean isMultiOrderReference(StructuralRoutingFeatures features) {
        return features.distinctOrderReferences() >= properties.minOrderReferencesForTier2();
    }

    /** True when the long-message signal fires for the given features. */
    public boolean isLongCompoundMessage(StructuralRoutingFeatures features) {
        return features.messageLength() >= properties.longMessageThreshold();
    }

    /** True when a procedure is active and another intent is deferred, and the message is a substantive request. */
    public boolean isComplexSessionState(StructuralRoutingFeatures features, String userMessage) {
        String text = userMessage == null ? "" : userMessage;
        return features.hasActiveProcedure()
                && features.hasDeferredIntent()
                && text.trim().length() >= SUBSTANTIVE_MESSAGE_MIN_LENGTH;
    }

    private static int countDistinctOrderReferences(String text) {
        return (int) ORDER_REFERENCE.matcher(text).results()
                .map(m -> m.group().toUpperCase())
                .distinct()
                .count();
    }
}
