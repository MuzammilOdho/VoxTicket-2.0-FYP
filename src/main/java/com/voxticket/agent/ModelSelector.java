package com.voxticket.agent;

import com.voxticket.conversation.ConversationSession;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Spec §53/§54. A small number of deterministic, understandable complexity
 * signals - not another model call (that would defeat the point), and not
 * one-model-per-intent (spec: "Do not map one model to one business
 * intent").
 *
 * <p>This phase has no multi-intent detection, procedure interactions, or
 * Tier-1-failure tracking yet - those need infrastructure from Phase 8/10
 * that doesn't exist. Only the signals genuinely available now are
 * implemented: message length, multiple order references in one message,
 * and simple conditional-language markers. The keyword/regex signals are
 * English-centric - a known limitation for Urdu/Roman Urdu input, worth
 * revisiting once Phase 10 looks at multilingual conversation quality
 * specifically. Getting this decision "wrong" only costs quality/latency,
 * never correctness or security - every tool available is identical at
 * either tier.
 */
@Component
public class ModelSelector {

    private static final int LONG_MESSAGE_THRESHOLD = 300;
    private static final Pattern ORDER_REFERENCE = Pattern.compile("\\bORD-\\w+\\b", Pattern.CASE_INSENSITIVE);
    private static final List<String> CONDITIONAL_MARKERS = List.of(" unless ", " if ", " but only if", " otherwise ");

    private final String tier1Model;
    private final String tier2Model;

    public ModelSelector(
            @Value("${voxticket.ai.tier1-model:openai/gpt-oss-20b}") String tier1Model,
            @Value("${voxticket.ai.tier2-model:openai/gpt-oss-120b}") String tier2Model) {
        this.tier1Model = tier1Model;
        this.tier2Model = tier2Model;
    }

    public ModelTier select(ConversationSession session, String userMessage) {
        String text = userMessage == null ? "" : userMessage;
        if (text.length() > LONG_MESSAGE_THRESHOLD) {
            return ModelTier.TIER_2;
        }
        if (countDistinctOrderReferences(text) > 1) {
            return ModelTier.TIER_2;
        }
        String padded = " " + text.toLowerCase() + " ";
        if (CONDITIONAL_MARKERS.stream().anyMatch(padded::contains)) {
            return ModelTier.TIER_2;
        }
        return ModelTier.TIER_1;
    }

    public String modelFor(ModelTier tier) {
        return tier == ModelTier.TIER_2 ? tier2Model : tier1Model;
    }

    private long countDistinctOrderReferences(String text) {
        return ORDER_REFERENCE.matcher(text).results().map(m -> m.group().toUpperCase()).distinct().count();
    }
}