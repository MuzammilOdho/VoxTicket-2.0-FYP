package com.voxticket.routing;

import com.voxticket.agent.ModelTier;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Phase 2: loads the versioned routing-example dataset (e.g.
 * {@code classpath:routing/examples-v1.json}).
 *
 * <p>This bean exists ONLY when {@code voxticket.ai.selector.strategy=HYBRID} (the default).
 * RULE_ONLY / ALWAYS_TIER_1 / ALWAYS_TIER_2 contexts never load the JSON dataset, never need
 * {@code ROUTING_MODEL_PATH}, and never touch the tokenizer or ONNX model.
 *
 * <p>Validation is strict on purpose: duplicate ids, unknown tiers/languages, blank texts, or a
 * class imbalance (a tier with zero examples) all fail fast at startup, because a silently
 * degraded prototype set would route arbitrarily.
 *
 * <p>Parses with Jackson 3 ({@code tools.jackson}), the JSON library Spring Boot 4
 * auto-configures. The injected {@code tools.jackson.databind.ObjectMapper} is the Boot 4
 * {@code JacksonAutoConfiguration} bean - never the Jackson 2
 * {@code com.fasterxml.jackson.databind.ObjectMapper}, which Boot 4 does not provide.
 */
@Component
@ConditionalOnProperty(name = "voxticket.ai.selector.strategy", havingValue = "HYBRID", matchIfMissing = true)
public class RoutingExampleLoader {

    private final ResourceLoader resourceLoader;
    private final ObjectMapper objectMapper;

    public RoutingExampleLoader(ResourceLoader resourceLoader, ObjectMapper objectMapper) {
        this.resourceLoader = resourceLoader;
        this.objectMapper = objectMapper;
    }

    public List<RoutingExample> load(String location) {
        Resource resource = resourceLoader.getResource(location);
        JsonNode root;
        try (InputStream in = resource.getInputStream()) {
            // Jackson 3: readTree throws the unchecked JacksonException on malformed JSON;
            // getInputStream throws the checked IOException on missing/unreadable resources.
            root = objectMapper.readTree(in);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("Cannot parse routing examples from " + location, e);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read routing examples from " + location, e);
        }
        JsonNode nodes = root.path("examples");
        if (!nodes.isArray() || nodes.isEmpty()) {
            throw new IllegalArgumentException("Routing examples at " + location
                    + " must contain a non-empty 'examples' array");
        }
        List<RoutingExample> examples = new ArrayList<>(nodes.size());
        Set<String> ids = new HashSet<>();
        EnumSet<ModelTier> tiersSeen = EnumSet.noneOf(ModelTier.class);
        for (JsonNode node : nodes) {
            RoutingExample example = parse(node, location);
            if (!ids.add(example.id())) {
                throw new IllegalArgumentException("Duplicate routing example id '"
                        + example.id() + "' in " + location);
            }
            tiersSeen.add(example.tier());
            examples.add(example);
        }
        if (tiersSeen.size() < ModelTier.values().length) {
            throw new IllegalArgumentException("Routing examples at " + location
                    + " must cover every tier; seen " + tiersSeen);
        }
        return List.copyOf(examples);
    }

    private static RoutingExample parse(JsonNode node, String location) {
        String id = node.path("id").asText("").trim();
        String tierName = node.path("tier").asText("").trim();
        String languageName = node.path("language").asText("").trim();
        String text = node.path("text").asText("").trim();
        if (id.isEmpty() || tierName.isEmpty() || languageName.isEmpty() || text.isEmpty()) {
            throw new IllegalArgumentException("Routing example with missing id/tier/language/text in "
                    + location + ": " + node);
        }
        final ModelTier tier;
        try {
            tier = ModelTier.valueOf(tierName);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown tier '" + tierName
                    + "' in routing example '" + id + "' (" + location + ")", e);
        }
        final RoutingExampleLanguage language;
        try {
            language = RoutingExampleLanguage.valueOf(languageName);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown language '" + languageName
                    + "' in routing example '" + id + "' (" + location + ")", e);
        }
        Set<String> tags = new HashSet<>();
        node.path("tags").forEach(t -> tags.add(t.asText()));
        return new RoutingExample(id, tier, language, Set.copyOf(tags), text);
    }
}
