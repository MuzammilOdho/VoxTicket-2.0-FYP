package com.voxticket.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.voxticket.config.PromptProperties;
import org.junit.jupiter.api.Test;

/**
 * P3.1: the versioned prompt template loads from the classpath, the version
 * identity is stable, and a missing template fails fast instead of running
 * the agent with the wrong (or no) system prompt.
 */
class PromptTemplateServiceTest {

    @Test
    void v1LoadsWithVersionAndContentHash() {
        PromptTemplateService service = new PromptTemplateService(new PromptProperties("v1"));

        PromptVersion version = service.current();
        assertThat(version.version()).isEqualTo("v1");
        assertThat(version.templateName()).isEqualTo("system");
        assertThat(version.contentHash()).matches("[0-9a-f]{64}");

        String base = service.systemPromptBase();
        assertThat(base).startsWith("You are VoxTicket, an e-commerce customer-support assistant.");
        assertThat(base).contains("COMMUNICATION");
        assertThat(base).contains("SAFETY");
        assertThat(base).endsWith("\n");
    }

    @Test
    void contentHashIsStableAcrossLoads() {
        PromptVersion first = new PromptTemplateService(new PromptProperties("v1")).current();
        PromptVersion second = new PromptTemplateService(new PromptProperties("v1")).current();

        assertThat(second.contentHash()).isEqualTo(first.contentHash());
    }

    @Test
    void unknownVersionFailsFast() {
        assertThatThrownBy(() -> new PromptTemplateService(new PromptProperties("v99")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("prompts/system-v99.txt");
    }
}
