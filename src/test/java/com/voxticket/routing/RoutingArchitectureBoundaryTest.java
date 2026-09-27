package com.voxticket.routing;

import com.voxticket.agent.ModelSelector;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2: architecture boundaries. The router decides ONLY the tier - it must never name a
 * provider or model, never touch authorization/OTP/eligibility, and never fall back between
 * providers or strategies.
 */
class RoutingArchitectureBoundaryTest {

    private static final Set<String> FORBIDDEN_FRAGMENTS = Set.of(
            "provider", "model", "apikey", "api_key", "otp", "authoriz", "fallback");

    /**
     * Allowed: {@code SemanticRoutingProperties#model} / {@code #modelPath} name the LOCAL
     * routing artifact (fixed to {@code intfloat/multilingual-e5-small}, validated at startup).
     * Naming the artifact is configuration, not a provider/model decision - the router still
     * outputs only a tier.
     */
    private static final Set<String> ALLOWED = Set.of(
            "SemanticRoutingProperties#model", "SemanticRoutingProperties#modelPath");

    /** Every production class under com.voxticket.routing, including the embedding subpackage. */
    private static Set<Class<?>> routingClasses() {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(Object.class));
        return scanner.findCandidateComponents("com.voxticket.routing").stream()
                .map(BeanDefinition::getBeanClassName)
                .map(name -> {
                    try {
                        return Class.forName(name);
                    } catch (ClassNotFoundException e) {
                        throw new IllegalStateException(e);
                    }
                })
                // Boundaries govern production code; test-class names (e.g. *ModelDirectory* tests)
                // would otherwise trip the fragment scan.
                .filter(c -> !c.getSimpleName().endsWith("Test"))
                .collect(Collectors.toSet());
    }

    @Test
    void noRoutingClassMentionsProvidersModelsOtpOrFallback() {
        var violations = routingClasses().stream()
                .flatMap(c -> Arrays.stream(c.getDeclaredMethods()).map(m -> c.getSimpleName() + "#" + m.getName()))
                .filter(name -> !ALLOWED.contains(name))
                .filter(name -> FORBIDDEN_FRAGMENTS.stream().anyMatch(frag -> name.toLowerCase().contains(frag)))
                .toList();

        assertThat(violations).isEmpty();
    }

    @Test
    void noRoutingClassHasProviderOrModelTypedParameters() {
        var violations = routingClasses().stream()
                .flatMap(c -> Arrays.stream(c.getDeclaredMethods()))
                .filter(m -> Arrays.stream(m.getParameters())
                        .map(Parameter::getType)
                        .map(Class::getSimpleName)
                        .anyMatch(t -> t.contains("Provider") || t.contains("ChatClient") || t.contains("ChatModel")))
                .map(Method::toString)
                .toList();

        assertThat(violations).isEmpty();
    }

    @Test
    void routingReasonsCarryNoProviderOrModelInformation() {
        for (RoutingReason reason : RoutingReason.values()) {
            assertThat(reason.name()).doesNotContain("PROVIDER").doesNotContain("MODEL");
        }
    }

    @Test
    void modelSelectorHasNoProviderOrModelMethods() {
        assertThat(Arrays.stream(ModelSelector.class.getMethods()).map(Method::getName))
                .doesNotContain("modelFor", "providerFor", "model", "provider", "fallback");
    }

    @Test
    void routingDecisionsCannotNameProviders() {
        var recordComponents = Arrays.stream(RoutingDecision.class.getRecordComponents())
                .map(c -> c.getName())
                .toList();

        assertThat(recordComponents).containsExactly("tier", "reason", "simpleScore", "complexScore", "margin", "signals");
    }

    @Test
    void configurationPrefixHoldsNoProviderOrModelKeys() {
        var prefix = RoutingProperties.class.getAnnotation(
                org.springframework.boot.context.properties.ConfigurationProperties.class).prefix();

        assertThat(prefix).isEqualTo("voxticket.ai.selector");
        assertThat(prefix).doesNotContain("provider").doesNotContain("model");
    }
}
