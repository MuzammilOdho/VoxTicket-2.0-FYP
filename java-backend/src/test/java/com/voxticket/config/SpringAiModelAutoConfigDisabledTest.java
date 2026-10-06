package com.voxticket.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.voxticket.agent.AiProvider;
import com.voxticket.agent.ProviderChatModelFactory;
import com.voxticket.agent.ProviderProperties;
import com.voxticket.agent.TierChatProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.io.InputStream;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.audio.transcription.TranscriptionModel;
import org.springframework.ai.audio.tts.TextToSpeechModel;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.image.ImageModel;
import org.springframework.ai.moderation.ModerationModel;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration;
import org.springframework.ai.model.ollama.autoconfigure.OllamaApiAutoConfiguration;
import org.springframework.ai.model.ollama.autoconfigure.OllamaEmbeddingAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiAudioSpeechAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiAudioTranscriptionAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiImageAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiModerationAutoConfiguration;
import org.yaml.snakeyaml.Yaml;

/**
 * Regression coverage for the Spring AI auto-configuration hardening.
 *
 * <p>The production application uses ONLY manually constructed tier chat
 * clients (TierChatClientRegistry -> ProviderChatModelFactory) plus Ollama
 * embeddings. The unused OpenAI model auto-configurations
 * (speech/transcription/image/moderation) previously activated by default
 * (their {@code spring.ai.model.*} conditions are matchIfMissing=openai) and
 * failed context startup without OpenAI credentials. The global
 * ChatClient.Builder auto-configuration is unused as well - nothing injects
 * it (verified by codebase search).
 *
 * <p>These tests read the REAL application.yaml, so they fail if the disables
 * are ever removed from configuration - they cannot drift from the shipped
 * config.
 */
class SpringAiModelAutoConfigDisabledTest {

    /** The spring.ai.model.* / spring.ai.chat.client.* values from the real application.yaml. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> springAiModelConfig() {
        try (InputStream in = SpringAiModelAutoConfigDisabledTest.class.getClassLoader()
                .getResourceAsStream("application.yaml")) {
            assertThat(in).as("application.yaml must be on the test classpath").isNotNull();
            Map<String, Object> yaml = new Yaml().load(in);
            Map<String, Object> spring = (Map<String, Object>) yaml.get("spring");
            Map<String, Object> ai = (Map<String, Object>) spring.get("ai");
            return (Map<String, Object>) ai.get("model");
        } catch (Exception e) {
            throw new AssertionError("could not read application.yaml", e);
        }
    }

    private static String modelValue(Map<String, Object> model, String... path) {
        Object current = model;
        for (String segment : path) {
            current = ((Map<String, Object>) current).get(segment);
        }
        return String.valueOf(current);
    }

    private static String chatClientEnabled() {
        try (InputStream in = SpringAiModelAutoConfigDisabledTest.class.getClassLoader()
                .getResourceAsStream("application.yaml")) {
            Map<String, Object> yaml = new Yaml().load(in);
            Map<String, Object> spring = (Map<String, Object>) yaml.get("spring");
            Map<String, Object> ai = (Map<String, Object>) spring.get("ai");
            Map<String, Object> chat = (Map<String, Object>) ai.get("chat");
            Map<String, Object> client = (Map<String, Object>) chat.get("client");
            return String.valueOf(client.get("enabled"));
        } catch (Exception e) {
            throw new AssertionError("could not read application.yaml", e);
        }
    }

    @Test
    void applicationYamlDisablesEveryUnusedModelSelector() {
        Map<String, Object> model = springAiModelConfig();

        assertThat(modelValue(model, "chat")).isEqualTo("none");
        assertThat(modelValue(model, "embedding")).isEqualTo("ollama");
        assertThat(modelValue(model, "audio", "speech")).isEqualTo("none");
        assertThat(modelValue(model, "audio", "transcription")).isEqualTo("none");
        assertThat(modelValue(model, "image")).isEqualTo("none");
        assertThat(modelValue(model, "moderation")).isEqualTo("none");
        assertThat(chatClientEnabled()).isEqualTo("false");
    }

    private static ApplicationContextRunner autoConfigRunner() {
        Map<String, Object> model = springAiModelConfig();
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        OpenAiAudioSpeechAutoConfiguration.class,
                        OpenAiAudioTranscriptionAutoConfiguration.class,
                        OpenAiImageAutoConfiguration.class,
                        OpenAiModerationAutoConfiguration.class,
                        ChatClientAutoConfiguration.class,
                        OllamaApiAutoConfiguration.class,
                        OllamaEmbeddingAutoConfiguration.class))
                .withPropertyValues(
                        "spring.ai.model.chat=" + modelValue(model, "chat"),
                        "spring.ai.model.embedding=" + modelValue(model, "embedding"),
                        "spring.ai.model.audio.speech=" + modelValue(model, "audio", "speech"),
                        "spring.ai.model.audio.transcription=" + modelValue(model, "audio", "transcription"),
                        "spring.ai.model.image=" + modelValue(model, "image"),
                        "spring.ai.model.moderation=" + modelValue(model, "moderation"),
                        "spring.ai.chat.client.enabled=" + chatClientEnabled(),
                        "spring.ai.ollama.embedding.model=bge-m3");
    }

    @Test
    void unusedOpenAiModelAutoConfigsStayInactiveWithoutCredentials() {
        autoConfigRunner().run(context -> {
            assertThat(context).as("no auto-config bean may fail without credentials").hasNotFailed();
            assertThat(context).doesNotHaveBean(TextToSpeechModel.class);
            assertThat(context).doesNotHaveBean(TranscriptionModel.class);
            assertThat(context).doesNotHaveBean(ImageModel.class);
            assertThat(context).doesNotHaveBean(ModerationModel.class);
        });
    }

    @Test
    void globalChatClientBuilderIsDisabled() {
        autoConfigRunner().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(ChatClient.Builder.class);
        });
    }

    @Test
    void ollamaEmbeddingAutoConfigRemainsActive() {
        autoConfigRunner().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(EmbeddingModel.class);
        });
    }

    @Test
    void enablingOpenAiSpeechWithoutCredentialsFailsTheContext() {
        // Negative control: proves the assertions above are not vacuous - with
        // the default (openai) selector and no API key, the speech
        // auto-configuration really does break context startup.
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(OpenAiAudioSpeechAutoConfiguration.class))
                .withPropertyValues("spring.ai.model.audio.speech=openai")
                .run(context -> assertThat(context)
                        .as("OpenAI speech auto-config without credentials must fail")
                        .hasFailed());
    }

    @Test
    void manualTierProviderClientsStillConstruct() {
        // The disabled auto-configurations must not take the manual tier
        // clients down with them: every provider path still builds from
        // configuration alone, with no network I/O.
        ProviderChatModelFactory factory = new ProviderChatModelFactory();
        var tier = new TierChatProperties(AiProvider.GROQ, "openai/gpt-oss-20b", 0.3, 1024, "none", 20, 0);

        assertThat(factory.chatModelFor(AiProvider.GOOGLE,
                new ProviderProperties(true, "dummy", null),
                new TierChatProperties(AiProvider.GOOGLE, "gemini-3.6-flash", 0.3, 1024, "none", 20, 0),
                ObservationRegistry.NOOP, new SimpleMeterRegistry())).isNotNull();
        assertThat(factory.chatModelFor(AiProvider.GROQ,
                new ProviderProperties(true, "dummy", "https://api.groq.com/openai/v1"),
                tier, ObservationRegistry.NOOP, new SimpleMeterRegistry())).isNotNull();
        assertThat(factory.chatModelFor(AiProvider.CEREBRAS,
                new ProviderProperties(true, "dummy", "https://api.cerebras.ai/v1"),
                new TierChatProperties(AiProvider.CEREBRAS, "gpt-oss-120b", 0.3, 1024, "high", 20, 0),
                ObservationRegistry.NOOP, new SimpleMeterRegistry())).isNotNull();
    }
}
