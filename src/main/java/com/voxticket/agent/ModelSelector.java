package com.voxticket.agent;

import com.voxticket.conversation.ConversationSession;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Spec §53/§54. A small number of deterministic, understandable complexity
 * signals - not another model call, and not one-model-per-intent. Model
 * names and routing thresholds are both externalized (ModelTierProperties,
 * ModelSelectorProperties) - this class contains no configuration values
 * of its own.
 */
@Component
public class ModelSelector {

    private static final Pattern ORDER_REFERENCE = Pattern.compile("\\bORD-\\w+\\b", Pattern.CASE_INSENSITIVE);
    private static final List<String> CONDITIONAL_MARKERS = List.of(" unless ", " if ", " but only if", " otherwise ");

    private final ModelTierProperties modelTierProperties;
    private final ModelSelectorProperties selectorProperties;

    public ModelSelector(ModelTierProperties modelTierProperties, ModelSelectorProperties selectorProperties) {
        this.modelTierProperties = modelTierProperties;
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

    public String modelFor(ModelTier tier) {
        return tier == ModelTier.TIER_2 ? modelTierProperties.tier2Model() : modelTierProperties.tier1Model();
    }

    private long countDistinctOrderReferences(String text) {
        return ORDER_REFERENCE.matcher(text).results().map(m -> m.group().toUpperCase()).distinct().count();
    }
}