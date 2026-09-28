package com.voxticket.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.voxticket.agent.ModelSelector;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Phase 2: architecture boundaries. The router decides ONLY the tier - it must never name a
 * provider or model, never touch authorization/OTP/eligibility, and never fall back between
 * providers or strategies.
 *
 * <p>All scans below are anchored to the <em>production</em> classes root (resolved from a
 * known production class's code source). Test and evaluation classes live under a different
 * classes root and are excluded by origin - not by name pattern - so current and future
 * research harnesses can never trip these production boundaries.
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

    /**
     * The production classes root, resolved from a known production class's code source
     * (e.g. {@code target/classes} under Maven). Anchoring the scan here - instead of
     * scanning the whole test classpath - is what keeps test/evaluation classes out of
     * these production boundaries, no matter what they are named.
     */
    private static Path productionClassesRoot() {
        var location = RoutingDecision.class.getProtectionDomain().getCodeSource().getLocation();
        assertThat(location).as("production code-source location").isNotNull();
        try {
            return Path.of(location.toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Cannot resolve production classes root", e);
        }
    }

    private static Path codeSourcePath(Class<?> c) {
        var location = c.getProtectionDomain().getCodeSource().getLocation();
        assertThat(location).as("code source of %s", c.getName()).isNotNull();
        try {
            return Path.of(location.toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Cannot resolve code source of " + c.getName(), e);
        }
    }

    /**
     * Every production class under com.voxticket.routing, including the embedding subpackage
     * and nested classes. Test classes (including the Phase 3 {@code routing.eval} research
     * harness) are compiled to a different classes root and can never appear here.
     */
    private static Set<Class<?>> routingClasses() {
        Path classesRoot = productionClassesRoot();
        Path routingRoot = classesRoot.resolve(Path.of("com", "voxticket", "routing"));
        try (var paths = Files.walk(routingRoot)) {
            return paths.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".class"))
                    .map(classesRoot::relativize)
                    .map(p -> p.toString()
                            .replace(File.separatorChar, '.')
                            .replaceAll("\\.class$", ""))
                    .map(name -> {
                        try {
                            return Class.forName(name);
                        } catch (ClassNotFoundException e) {
                            throw new IllegalStateException(
                                    "Cannot load production routing class " + name, e);
                        }
                    })
                    .collect(Collectors.toSet());
        } catch (IOException e) {
            throw new IllegalStateException("Cannot scan production routing classes", e);
        }
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

    /**
     * Regression guard: the production scan above must see production classes only. The Phase 3
     * research harness ({@code com.voxticket.routing.eval}) sits on the test classpath and its
     * names trip the forbidden fragments ({@code Phase3ProviderE2E} contains "provider",
     * {@code RunInfo} declares {@code model()}/{@code modelInitMs()}) - if it ever leaked into
     * the scan, {@link #noRoutingClassMentionsProvidersModelsOtpOrFallback()} would fail for
     * reasons unrelated to production code. Exclusion is by class-file origin, so future
     * eval/research classes are covered without any name-pattern maintenance.
     */
    @Test
    void productionScanExcludesTestAndEvalClasses() {
        Set<Class<?>> scanned = routingClasses();
        assertThat(scanned).as("production routing scan").isNotEmpty();

        // Load-bearing: these test-scoped classes exist on the test classpath...
        assertThatCode(() -> Class.forName("com.voxticket.routing.eval.Phase3ProviderE2E"))
                .doesNotThrowAnyException();
        assertThatCode(() -> Class.forName("com.voxticket.routing.eval.Phase3EvaluationSuite$RunInfo"))
                .doesNotThrowAnyException();

        // ...but none of them may leak into the production boundary scan.
        assertThat(scanned.stream().map(Class::getName).collect(Collectors.toSet()))
                .noneMatch(n -> n.startsWith("com.voxticket.routing.eval."));

        // Belt and braces: every scanned class really comes from the production classes root.
        Path root = productionClassesRoot();
        for (Class<?> c : scanned) {
            assertThat(codeSourcePath(c)).as("code source of %s", c.getName()).isEqualTo(root);
        }
    }
}
