package com.voxticket.admin;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Estimated AI spend, bound from {@code voxticket.ai.cost}.
 *
 * <p>USD per 1k tokens, keyed by model id exactly as configured in
 * {@code voxticket.ai.tiers.*.model}. Consumed read-only by
 * {@link AiAnalyticsService} for {@code GET /api/v1/admin/ai/cost}.
 * A price of 0.0 (or a missing entry) means "unknown" and is excluded
 * from the estimate rather than fabricated.
 */
@ConfigurationProperties(prefix = "voxticket.ai.cost")
public record AdminCostProperties(Map<String, Double> per1kTokens) {

    public AdminCostProperties {
        per1kTokens = per1kTokens == null ? Map.of() : Map.copyOf(per1kTokens);
    }

    /** USD per 1k tokens for the model, or 0.0 when unknown. Never negative. */
    public double pricePer1kTokens(String model) {
        if (model == null) {
            return 0.0;
        }
        Double price = per1kTokens.get(model);
        return price == null || price < 0.0 ? 0.0 : price;
    }
}
