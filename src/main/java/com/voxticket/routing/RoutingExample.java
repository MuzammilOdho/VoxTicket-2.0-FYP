package com.voxticket.routing;

import com.voxticket.agent.ModelTier;
import java.util.Set;

/**
 * Phase 2: one routing prototype example.
 *
 * <p>Router configuration / training context only - this is NOT the Phase 3 held-out evaluation
 * dataset. Each example is embedded once at startup (with the e5 {@code "query: "} prefix) and
 * the normalized vector is cached in {@code RoutingExampleIndex}; per-request work is pure
 * cosine arithmetic against the cache.
 */
public record RoutingExample(
        String id,
        ModelTier tier,
        RoutingExampleLanguage language,
        Set<String> tags,
        String text) {
}
