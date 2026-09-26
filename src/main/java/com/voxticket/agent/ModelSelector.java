package com.voxticket.agent;

import com.voxticket.conversation.ConversationSession;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Spec §53/§54. A small number of deterministic, understandable complexity
 * signals - not another model call, and not one-model-per-intent.
 *
 * <p>Phase 1: this class decides ONLY the tier, never the provider. Which
 * provider and model serve a tier is pure configuration
 * ({@link AiTiersProperties} + {@link TierChatClientRegistry}) - the
 * selection result carries the tier and the routing reason, and nothing
 * provider- or model-specific.
 */
@Component
public class ModelSelector {

    private static final Pattern ORDER_REFERENCE = Pattern.compile("\\bORD-\\w+\\b", Pattern.CASE_INSENSITIVE);
    private static final List<String> CONDITIONAL_MARKERS = List.of(" unless ", " if ", " but only if", " otherwise ");

    private final ModelSelectorProperties selectorProperties;

    public ModelSelector(ModelSelectorProperties selectorProperties) {
        this.selectorProperties = selectorProperties;
    }

    public ModelSelectionResult select(ConversationSession session, String userMessage) {
        String text = userMessage == null ? "" : userMessage;
        if (text.length() > selectorProperties.longMessageThreshold()) {
            return new ModelSelectionResult(ModelTier.TIER_2, "long_message");
        }
        if (countDistinctOrderReferences(text) >= selectorProperties.minOrderReferencesForTier2()) {
            return new ModelSelectionResult(ModelTier.TIER_2, "multi_order_reference");
        }
        String padded = " " + text.toLowerCase() + " ";
        if (CONDITIONAL_MARKERS.stream().anyMatch(padded::contains)) {
            return new ModelSelectionResult(ModelTier.TIER_2, "conditional_language");
        }
        return new ModelSelectionResult(ModelTier.TIER_1, "default");
    }

    private long countDistinctOrderReferences(String text) {
        return ORDER_REFERENCE.matcher(text).results().map(m -> m.group().toUpperCase()).distinct().count();
    }
}
