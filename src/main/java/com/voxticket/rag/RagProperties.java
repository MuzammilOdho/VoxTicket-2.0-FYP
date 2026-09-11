package com.voxticket.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "voxticket.rag")
public record RagProperties(
        @DefaultValue("3") int topK,
        @DefaultValue("0.5") double similarityThreshold) {
}