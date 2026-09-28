package com.voxticket.routing.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.voxticket.agent.ModelTier;
import com.voxticket.conversation.Channel;
import com.voxticket.conversation.ConversationSession;
import com.voxticket.routing.RoutingDecision;
import com.voxticket.routing.RoutingDecisionEngine;
import com.voxticket.routing.RoutingEmbeddingService;
import com.voxticket.routing.RoutingExample;
import com.voxticket.routing.RoutingExampleIndex;
import com.voxticket.routing.RoutingExampleLoader;
import com.voxticket.routing.RoutingProperties;
import com.voxticket.routing.RoutingReason;
import com.voxticket.routing.RoutingStrategy;
import com.voxticket.routing.SemanticRoutingProperties;
import com.voxticket.routing.SemanticRoutingService;
import com.voxticket.routing.SemanticScores;
import com.voxticket.routing.StructuralFeatureExtractor;
import com.voxticket.routing.StructuralRoutingFeatures;
import com.voxticket.routing.StructuralRoutingProperties;
import com.voxticket.routing.StructuralRoutingSignal;
import com.voxticket.routing.embedding.OnnxMultilingualE5EmbeddingService;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.core.io.DefaultResourceLoader;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Phase 3: the real-model evaluation run. OPT-IN ONLY - never runs in a normal
 * {@code mvn clean test}.
 *
 * <p>Enable with:
 * <pre>
 * -Dvoxticket.phase3.eval=true
 * -Dvoxticket.routing.model.path=/home/hatch/workspace/models/e5
 * -Dvoxticket.phase3.git-sha=&lt;git rev-parse HEAD&gt;
 * </pre>
 *
 * <p>Ordered steps: (1) inference over both splits with the real ONNX model,
 * writing the inference caches; (2) validation baseline at the Phase 2 placeholder
 * margins; (3) calibration on VALIDATION only, freezing the threshold;
 * (4) the single held-out FINAL_TEST strategy comparison; (5) the latency
 * benchmark. The suite asserts structural invariants (embedding budget, cache
 * round-trips, selector parity) - the measured numbers themselves are findings,
 * not assertions.
 *
 * <p>No DB, no network, no LLM provider call anywhere in this suite.
 */
@EnabledIfSystemProperty(named = "voxticket.phase3.eval", matches = "true")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Phase3EvaluationSuite {

    private static final Path CORPUS_DIR = Paths.get("evaluation/routing/corpus");
    private static final Path RESULTS_DIR = Paths.get("evaluation/routing/results");
    private static final JsonMapper JSON = new JsonMapper();

    private static Phase3Router router;
    private static RoutingProperties properties;
    private static List<EvalExample> validation;
    private static List<EvalExample> finalTest;
    private static RunInfo runInfo;
    private static double frozenMargin = Double.NaN;
    /** The raw ONNX backend (no cache) - kept for the latency benchmark's real-inference path. */
    private static OnnxMultilingualE5EmbeddingService rawEmbeddingService;

    /** One cached inference row (written by step 1, read back by steps 2-4). */
    record CachedTurn(
            String id,
            EvalSplit split,
            EvalLanguage language,
            ModelTier expectedTier,
            String text,
            List<String> tags,
            List<String> signals,
            Double simpleScore,
            Double complexScore,
            Double margin) {
    }

    record RunInfo(
            String timestamp,
            String gitSha,
            String model,
            String onnxSha256,
            String tokenizerSha256,
            String prototypeHash,
            String validationCorpusHash,
            String finalTestCorpusHash,
            int topK,
            double originalSimpleMargin,
            double originalComplexMargin,
            String javaVersion,
            String onnxRuntimeVersion,
            long modelInitMs,
            long prototypeIndexInitMs,
            int prototypeCountTier1,
            int prototypeCountTier2) {
    }

    /**
     * Test-only embedding decorator: delegates every call to the real ONNX backend
     * with NO caching, counting genuine inference calls. Lets the latency benchmark
     * prove that no cached query embedding was reused during measurement.
     */
    static final class CountingEmbeddingService implements RoutingEmbeddingService {
        private final RoutingEmbeddingService delegate;
        private final AtomicLong calls = new AtomicLong();

        CountingEmbeddingService(RoutingEmbeddingService delegate) {
            this.delegate = delegate;
        }

        @Override
        public float[] embed(String text) {
            calls.incrementAndGet();
            return delegate.embed(text);
        }

        long calls() {
            return calls.get();
        }
    }

    @BeforeAll
    static void setUp() throws Exception {
        String modelPath = System.getProperty("voxticket.routing.model.path");
        if (modelPath == null || modelPath.isBlank()) {
            fail("Phase 3 evaluation needs -Dvoxticket.routing.model.path=/path/to/model/dir "
                    + "(dir holding model.onnx + tokenizer.json)");
        }
        Files.createDirectories(RESULTS_DIR);

        validation = EvalCorpus.load(CORPUS_DIR.resolve("validation.json"));
        finalTest = EvalCorpus.load(CORPUS_DIR.resolve("final-test.json"));
        assertThat(validation).hasSize(120);
        assertThat(finalTest).hasSize(120);

        properties = new RoutingProperties(
                RoutingStrategy.HYBRID,
                new StructuralRoutingProperties(300, 2),
                new SemanticRoutingProperties(true, "intfloat/multilingual-e5-small", modelPath,
                        "classpath:routing/examples-v1.json", 3, 0.02, 0.02));

        long t0 = System.nanoTime();
        OnnxMultilingualE5EmbeddingService embeddingService =
                new OnnxMultilingualE5EmbeddingService(Paths.get(modelPath));
        rawEmbeddingService = embeddingService;
        long t1 = System.nanoTime();
        router = Phase3Router.create(properties, embeddingService);
        long t2 = System.nanoTime();

        Map<String, Integer> indexCounts = router.semanticRoutingService().describeIndex().stream()
                .collect(Collectors.toMap(
                        s -> s.split("=")[0], s -> Integer.parseInt(s.split("=")[1])));

        runInfo = new RunInfo(
                Instant.now().toString(),
                System.getProperty("voxticket.phase3.git-sha", "unknown"),
                properties.semantic().model(),
                sha256(Paths.get(modelPath, "model.onnx")),
                sha256(Paths.get(modelPath, "tokenizer.json")),
                sha256Resource("routing/examples-v1.json"),
                sha256(CORPUS_DIR.resolve("validation.json")),
                sha256(CORPUS_DIR.resolve("final-test.json")),
                properties.semantic().topKPerClass(),
                properties.semantic().simpleMargin(),
                properties.semantic().complexMargin(),
                System.getProperty("java.version"),
                onnxRuntimeVersion(),
                (t1 - t0) / 1_000_000,
                (t2 - t1) / 1_000_000,
                indexCounts.getOrDefault("TIER_1", -1),
                indexCounts.getOrDefault("TIER_2", -1));
        JSON.writerWithDefaultPrettyPrinter()
                .writeValue(RESULTS_DIR.resolve("run-info.json").toFile(), runInfo);
        System.out.println("Phase3 run-info: " + runInfo);
    }

    @Test
    @Order(1)
    void inferenceOverBothSplits() throws Exception {
        List<ScoredExample> scoredValidation = router.scoreForHybrid(validation);
        List<ScoredExample> scoredFinalTest = router.scoreForHybrid(finalTest);

        long semanticTurns = scoredValidation.stream().filter(s -> !s.structuralFired()).count()
                + scoredFinalTest.stream().filter(s -> !s.structuralFired()).count();
        // 96 prototype embeddings + exactly one query embedding per semantic turn.
        assertThat(router.embeddingCalls())
                .as("embedding budget: 96 prototypes + 1 per semantic turn")
                .isEqualTo(96 + semanticTurns);

        writeCache("inference-cache-validation.json", scoredValidation);
        writeCache("inference-cache-final-test.json", scoredFinalTest);

        // Round-trip: steps 2-4 must work from the files, not from memory.
        assertThat(readCache("inference-cache-validation.json")).hasSize(120);
        assertThat(readCache("inference-cache-final-test.json")).hasSize(120);

        // Light production-parity sample (cache-backed: no new real embeddings).
        List<EvalExample> sample = new ArrayList<>();
        for (EvalLanguage language : EvalLanguage.values()) {
            sample.addAll(validation.stream()
                    .filter(e -> e.language() == language).limit(2).toList());
        }
        List<String> mismatches = router.parityCheck(sample);
        assertThat(mismatches).as("selector parity: %s", mismatches).isEmpty();

        System.out.println("Phase3 inference: validation=" + scoredValidation.size()
                + " finalTest=" + scoredFinalTest.size()
                + " semanticTurns=" + semanticTurns
                + " embeddingCalls=" + router.embeddingCalls());
    }

    @Test
    @Order(2)
    void baselineValidation() throws Exception {
        List<ScoredExample> scored = readCache("inference-cache-validation.json");
        RoutingMetrics.Metrics overall = ThresholdCalibrator.evaluateAt(
                scored, router.decisionEngine(), properties.semantic(), 0.02);

        Map<String, RoutingMetrics.Metrics> byLanguage = new TreeMap<>();
        for (EvalLanguage language : EvalLanguage.values()) {
            byLanguage.put(language.name(), metricsFor(scored, language, 0.02));
        }
        Map<String, RoutingMetrics.Metrics> byTag = new TreeMap<>();
        Map<String, Long> tagCounts = new TreeMap<>();
        for (String tag : allTags(scored)) {
            List<RoutingMetrics.Prediction> preds = predictionsForTag(scored, tag, 0.02);
            tagCounts.put(tag, (long) preds.size());
            if (preds.size() >= 8) {
                byTag.put(tag, RoutingMetrics.compute(preds));
            }
        }

        long structural = scored.stream().filter(ScoredExample::structuralFired).count();
        Map<String, Long> reasons = reasonDistribution(scored, 0.02);

        ObjectNode root = JSON.createObjectNode();
        root.put("split", "VALIDATION");
        root.put("simpleMargin", 0.02);
        root.put("complexMargin", 0.02);
        root.put("topK", properties.semantic().topKPerClass());
        root.set("overall", JSON.valueToTree(overall));
        ObjectNode langs = root.putObject("byLanguage");
        byLanguage.forEach((k, v) -> langs.set(k, JSON.valueToTree(v)));
        ObjectNode tags = root.putObject("byTag");
        byTag.forEach((k, v) -> tags.set(k, JSON.valueToTree(v)));
        ObjectNode counts = root.putObject("tagSampleCounts");
        tagCounts.forEach(counts::put);
        root.put("structuralCoverage", (double) structural / scored.size());
        root.put("structuralCount", structural);
        root.put("semanticSimpleCount", reasons.getOrDefault("SEMANTIC_SIMPLE", 0L));
        root.put("semanticComplexCount", reasons.getOrDefault("SEMANTIC_COMPLEX", 0L));
        root.put("semanticAmbiguousCount", reasons.getOrDefault("SEMANTIC_AMBIGUOUS", 0L));
        ObjectNode reasonCounts = root.putObject("reasonCounts");
        reasons.forEach(reasonCounts::put);
        JSON.writerWithDefaultPrettyPrinter()
                .writeValue(RESULTS_DIR.resolve("baseline-validation.json").toFile(), root);
        System.out.println("Phase3 baseline VALIDATION: " + summarize(overall));
    }

    @Test
    @Order(3)
    void calibrateOnValidationOnly() throws Exception {
        List<ScoredExample> scored = readCache("inference-cache-validation.json");
        ThresholdCalibrator.CalibrationResult result = ThresholdCalibrator.calibrate(
                scored, router.decisionEngine(), properties.semantic());
        frozenMargin = result.selectedSimpleMargin();

        ObjectNode frozen = JSON.createObjectNode();
        frozen.put("selectedSimpleMargin", frozenMargin);
        frozen.put("complexMarginKeptAt", properties.semantic().complexMargin());
        frozen.put("complexMarginNote",
                "complexMargin only renames TIER_2 reasons (SEMANTIC_COMPLEX vs SEMANTIC_AMBIGUOUS); "
                        + "not identifiable from binary tier labels, kept at 0.02");
        frozen.put("selectionObjective",
                "maximize balanced accuracy; tie-breaks: lower under-routing, higher macro-F1, "
                        + "lower TIER_2 selection rate");
        frozen.put("calibratedOn", "VALIDATION");
        frozen.put("candidatesConsidered", result.candidateRows().size());
        JSON.writerWithDefaultPrettyPrinter()
                .writeValue(RESULTS_DIR.resolve("frozen-threshold.json").toFile(), frozen);

        StringBuilder csv = new StringBuilder(
                "simpleMargin,accuracy,balancedAccuracy,macroF1,t2Precision,t2Recall,t2F1,"
                        + "underRouted,underRouteRate,overRouted,overRouteRate,t2SelectionRate\n");
        ArrayNode rows = JSON.createArrayNode();
        for (ThresholdCalibrator.CandidateRow row : result.candidateRows()) {
            RoutingMetrics.Metrics m = row.metrics();
            csv.append(String.format(Locale.US,
                    "%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%d,%.6f,%d,%.6f,%.6f%n",
                    row.simpleMargin(), m.accuracy(), m.balancedAccuracy(), m.macroF1(),
                    m.t2Precision(), m.t2Recall(), m.t2F1(),
                    m.underRouted(), m.underRouteRate(),
                    m.overRouted(), m.overRouteRate(), m.t2SelectionRate()));
            ObjectNode r = JSON.createObjectNode();
            r.put("simpleMargin", row.simpleMargin());
            r.set("metrics", JSON.valueToTree(m));
            rows.add(r);
        }
        Files.writeString(RESULTS_DIR.resolve("calibration.csv"), csv.toString());
        ObjectNode calib = JSON.createObjectNode();
        calib.put("selectedSimpleMargin", frozenMargin);
        calib.set("beforeMetrics", JSON.valueToTree(result.beforeMetrics()));
        calib.set("afterMetrics", JSON.valueToTree(result.afterMetrics()));
        calib.set("candidateRows", rows);
        JSON.writerWithDefaultPrettyPrinter()
                .writeValue(RESULTS_DIR.resolve("calibration.json").toFile(), calib);

        System.out.println("Phase3 calibration: selected simpleMargin=" + frozenMargin
                + " before=" + summarize(result.beforeMetrics())
                + " after=" + summarize(result.afterMetrics()));
    }

    @Test
    @Order(4)
    void finalHeldOutTest() throws Exception {
        if (Double.isNaN(frozenMargin)) {
            // Re-runnable: read the frozen value from the artifact, never re-tune.
            frozenMargin = JSON.readTree(
                            RESULTS_DIR.resolve("frozen-threshold.json").toFile())
                    .get("selectedSimpleMargin").asDouble();
        }
        List<ScoredExample> scored = readCache("inference-cache-final-test.json");

        // The threshold is frozen: this step only reports, it never tunes.
        Map<String, RoutingMetrics.Metrics> byStrategy = new LinkedHashMap<>();
        byStrategy.put("ALWAYS_TIER_1",
                metricsForStrategy(scored, RoutingStrategy.ALWAYS_TIER_1, 0.02));
        byStrategy.put("ALWAYS_TIER_2",
                metricsForStrategy(scored, RoutingStrategy.ALWAYS_TIER_2, 0.02));
        byStrategy.put("RULE_ONLY", metricsForStrategy(scored, RoutingStrategy.RULE_ONLY, 0.02));
        byStrategy.put("HYBRID_CALIBRATED",
                metricsForStrategy(scored, RoutingStrategy.HYBRID, frozenMargin));

        StringBuilder csv = new StringBuilder(
                "strategy,accuracy,balancedAccuracy,macroF1,underRouted,underRouteRate,"
                        + "overRouted,overRouteRate,t2SelectionRate\n");
        ObjectNode root = JSON.createObjectNode();
        root.put("split", "FINAL_TEST");
        root.put("calibratedSimpleMargin", frozenMargin);
        ObjectNode strategies = root.putObject("strategies");
        for (Map.Entry<String, RoutingMetrics.Metrics> entry : byStrategy.entrySet()) {
            RoutingMetrics.Metrics m = entry.getValue();
            csv.append(String.format(Locale.US, "%s,%.6f,%.6f,%.6f,%d,%.6f,%d,%.6f,%.6f%n",
                    entry.getKey(), m.accuracy(), m.balancedAccuracy(), m.macroF1(),
                    m.underRouted(), m.underRouteRate(),
                    m.overRouted(), m.overRouteRate(), m.t2SelectionRate()));
            strategies.set(entry.getKey(), JSON.valueToTree(m));
        }
        Files.writeString(RESULTS_DIR.resolve("strategy-comparison.csv"), csv.toString());
        JSON.writerWithDefaultPrettyPrinter()
                .writeValue(RESULTS_DIR.resolve("strategy-comparison.json").toFile(), root);

        // HYBRID_CALIBRATED detail: per-language, confusion matrix, reason distribution.
        Map<String, RoutingMetrics.Metrics> byLanguage = new TreeMap<>();
        for (EvalLanguage language : EvalLanguage.values()) {
            byLanguage.put(language.name(), metricsFor(scored, language, frozenMargin));
        }
        StringBuilder langCsv = new StringBuilder(
                "language,n,accuracy,underRouted,overRouted,t2Recall,t2SelectionRate\n");
        ObjectNode langRoot = JSON.createObjectNode();
        langRoot.put("strategy", "HYBRID_CALIBRATED");
        ObjectNode langs = langRoot.putObject("byLanguage");
        for (Map.Entry<String, RoutingMetrics.Metrics> entry : byLanguage.entrySet()) {
            RoutingMetrics.Metrics m = entry.getValue();
            langCsv.append(String.format(Locale.US, "%s,%d,%.6f,%d,%d,%.6f,%.6f%n",
                    entry.getKey(), m.n(), m.accuracy(),
                    m.underRouted(), m.overRouted(), m.t2Recall(), m.t2SelectionRate()));
            langs.set(entry.getKey(), JSON.valueToTree(m));
        }
        Files.writeString(RESULTS_DIR.resolve("per-language.csv"), langCsv.toString());
        JSON.writerWithDefaultPrettyPrinter()
                .writeValue(RESULTS_DIR.resolve("per-language.json").toFile(), langRoot);

        RoutingMetrics.Metrics hybrid = byStrategy.get("HYBRID_CALIBRATED");
        JSON.writerWithDefaultPrettyPrinter().writeValue(
                RESULTS_DIR.resolve("confusion-matrix-final.json").toFile(),
                JSON.valueToTree(hybrid.confusionMatrix()));
        Map<String, Long> reasons = reasonDistribution(scored, frozenMargin);
        long structural = scored.stream().filter(ScoredExample::structuralFired).count();
        ObjectNode detail = JSON.createObjectNode();
        detail.put("t2Precision", hybrid.t2Precision());
        detail.put("t2Recall", hybrid.t2Recall());
        detail.put("t2F1", hybrid.t2F1());
        detail.put("structuralDecisions", structural);
        detail.put("semanticDecisions", scored.size() - structural);
        ObjectNode reasonCounts = detail.putObject("reasonCounts");
        reasons.forEach(reasonCounts::put);
        JSON.writerWithDefaultPrettyPrinter()
                .writeValue(RESULTS_DIR.resolve("hybrid-final-detail.json").toFile(), detail);

        byStrategy.forEach((name, m) ->
                System.out.println("Phase3 FINAL_TEST " + name + ": " + summarize(m)));
    }

    @Test
    @Order(5)
    void latencyBenchmark() throws Exception {
        List<ScoredExample> scored = readCache("inference-cache-validation.json");
        List<String> structuralTexts = scored.stream()
                .filter(ScoredExample::structuralFired)
                .map(s -> s.example().text()).toList();
        List<String> semanticTexts = scored.stream()
                .filter(s -> !s.structuralFired())
                .map(s -> s.example().text()).toList();
        assertThat(structuralTexts).as("need structural examples for latency").hasSizeGreaterThanOrEqualTo(2);
        assertThat(semanticTexts).as("need semantic examples for latency").hasSizeGreaterThanOrEqualTo(2);

        StructuralFeatureExtractor extractor = router.featureExtractor();
        RoutingDecisionEngine engine = router.decisionEngine();
        ConversationSession session =
                ConversationSession.newSession("phase3-latency", Channel.CHAT);

        // The frozen production semantics for the latency decision call
        // (same calibrated threshold as the final test).
        SemanticRoutingProperties frozenProps = withSimpleMargin(frozenMargin);

        // Latency-only semantic pipeline: the REAL production scoring path
        // (SemanticRoutingService.score) backed by the raw ONNX embedding service with
        // no cache, so every iteration performs genuine query tokenization + ONNX
        // inference + pooling + normalization. Prototype embeddings are NOT recomputed:
        // the latency index is rebuilt through the same CachingEmbeddingService whose
        // 96 prototype vectors were precomputed in step 1 (all cache hits, zero new
        // inference) - asserted below.
        RoutingExampleLoader loader =
                new RoutingExampleLoader(new DefaultResourceLoader(), new JsonMapper());
        List<RoutingExample> prototypes = loader.load(properties.semantic().examples());
        long prototypeCallsBefore = router.cachingEmbeddingService().realCalls();
        RoutingExampleIndex latencyIndex =
                RoutingExampleIndex.build(prototypes, router.cachingEmbeddingService());
        assertThat(router.cachingEmbeddingService().realCalls() - prototypeCallsBefore)
                .as("latency index must reuse precomputed prototype embeddings (zero new inference)")
                .isZero();
        CountingEmbeddingService countingEmbed = new CountingEmbeddingService(rawEmbeddingService);
        SemanticRoutingService latencySemantic =
                new SemanticRoutingService(countingEmbed, latencyIndex, frozenProps);

        // Structural signals are deterministic per text; precompute once (untimed) so the
        // timed semantic region is exactly: embed -> score -> decide.
        Map<String, Set<StructuralRoutingSignal>> signalsByText = new HashMap<>();
        for (String text : semanticTexts) {
            StructuralRoutingFeatures features = extractor.extract(session, text);
            signalsByText.put(text,
                    RoutingDecisionEngine.firedSignals(features, extractor, text));
        }

        // Warm up the ONNX runtime with the real pipeline (excluded from measurement).
        for (int i = 0; i < 100; i++) {
            String text = semanticTexts.get(i % semanticTexts.size());
            SemanticScores scores = latencySemantic.score(text);
            engine.decide(RoutingStrategy.HYBRID, signalsByText.get(text), scores, frozenProps);
        }

        // Structural short-circuit path: extract + signals + decide (no embedding).
        double[] structuralMs = new double[300];
        for (int i = 0; i < structuralMs.length; i++) {
            String text = structuralTexts.get(i % structuralTexts.size());
            long start = System.nanoTime();
            StructuralRoutingFeatures features = extractor.extract(session, text);
            Set<StructuralRoutingSignal> signals =
                    RoutingDecisionEngine.firedSignals(features, extractor, text);
            engine.decide(RoutingStrategy.HYBRID, signals, null, frozenProps);
            structuralMs[i] = (System.nanoTime() - start) / 1_000_000.0;
        }
        // Semantic path: real ONNX query embedding (tokenize + inference + pooling +
        // normalization) + similarity scoring against the precomputed prototypes +
        // production decision logic. No cached query embeddings or scores are reused:
        // the counting decorator proves every measured iteration ran inference.
        long callsBefore = countingEmbed.calls();
        double[] semanticMs = new double[300];
        for (int i = 0; i < semanticMs.length; i++) {
            String text = semanticTexts.get(i % semanticTexts.size());
            long start = System.nanoTime();
            SemanticScores scores = latencySemantic.score(text);
            engine.decide(RoutingStrategy.HYBRID, signalsByText.get(text), scores, frozenProps);
            semanticMs[i] = (System.nanoTime() - start) / 1_000_000.0;
        }
        assertThat(countingEmbed.calls() - callsBefore)
                .as("every measured semantic iteration must run real ONNX inference (no cache reuse)")
                .isEqualTo(semanticMs.length);

        ObjectNode root = JSON.createObjectNode();
        root.put("method", "System.nanoTime around the production routing path per turn; "
                + "100-iteration ONNX warmup on the real pipeline; model loading and prototype "
                + "indexing excluded from per-turn figures and reported separately in "
                + "run-info.json; 300 measured iterations per path, round-robin over all "
                + "validation texts taking that path. Every measured semantic iteration runs "
                + "genuine tokenization + ONNX inference + pooling + normalization + similarity "
                + "scoring + RoutingDecisionEngine.decide (asserted: 300 real embedding calls, "
                + "zero cache reuse); prototype embeddings precomputed once in step 1 and reused "
                + "(asserted: zero new prototype inference).");
        root.set("structuralShortCircuitMs", stats(structuralMs));
        root.set("semanticPathMs", stats(semanticMs));
        root.put("modelInitMs", runInfo.modelInitMs());
        root.put("prototypeIndexInitMs", runInfo.prototypeIndexInitMs());
        JSON.writerWithDefaultPrettyPrinter()
                .writeValue(RESULTS_DIR.resolve("latency.json").toFile(), root);

        String csv = "path,meanMs,p50Ms,p95Ms,maxMs\n"
                + String.format(Locale.US, "structural,%.4f,%.4f,%.4f,%.4f%n", mean(structuralMs),
                percentile(structuralMs, 50), percentile(structuralMs, 95), max(structuralMs))
                + String.format(Locale.US, "semantic,%.4f,%.4f,%.4f,%.4f%n", mean(semanticMs),
                percentile(semanticMs, 50), percentile(semanticMs, 95), max(semanticMs));
        Files.writeString(RESULTS_DIR.resolve("latency.csv"), csv);
        System.out.println("Phase3 latency (ms):\n" + csv);
    }

    // ------------------------------------------------------------------ helpers

    private void writeCache(String fileName, List<ScoredExample> scored) throws Exception {
        ArrayNode array = JSON.createArrayNode();
        for (ScoredExample s : scored) {
            ObjectNode row = JSON.createObjectNode();
            row.put("id", s.example().id());
            row.put("split", s.example().split().name());
            row.put("language", s.example().language().name());
            row.put("expectedTier", s.example().expectedTier().name());
            row.put("text", s.example().text());
            row.putPOJO("tags", s.example().tags());
            row.putPOJO("signals", s.signals().stream().map(Enum::name).toList());
            if (s.semanticScores() != null) {
                row.put("simpleScore", s.semanticScores().simpleScore());
                row.put("complexScore", s.semanticScores().complexScore());
                row.put("margin", s.semanticScores().margin());
            } else {
                row.putNull("simpleScore");
                row.putNull("complexScore");
                row.putNull("margin");
            }
            array.add(row);
        }
        ObjectNode root = JSON.createObjectNode();
        root.put("producedBy", "Phase3EvaluationSuite.inferenceOverBothSplits");
        root.put("model", runInfo.model());
        root.put("topK", runInfo.topK());
        root.set("examples", array);
        JSON.writerWithDefaultPrettyPrinter()
                .writeValue(RESULTS_DIR.resolve(fileName).toFile(), root);
    }

    private List<ScoredExample> readCache(String fileName) throws Exception {
        // The cache file is a wrapper {"examples": [...]}; steps 2-4 work from the files.
        tools.jackson.databind.JsonNode root =
                JSON.readTree(RESULTS_DIR.resolve(fileName).toFile());
        List<CachedTurn> rows = JSON.treeToValue(root.get("examples"),
                JSON.getTypeFactory().constructCollectionType(List.class, CachedTurn.class));
        return rows.stream().map(this::toScored).toList();
    }

    private ScoredExample toScored(CachedTurn row) {
        EvalExample example = new EvalExample(row.id(), row.split(), row.language(),
                row.expectedTier(), row.text(), row.tags(), "");
        Set<StructuralRoutingSignal> signals = row.signals().stream()
                .map(StructuralRoutingSignal::valueOf).collect(Collectors.toSet());
        SemanticScores scores = row.margin() == null ? null
                : new SemanticScores(row.simpleScore(), row.complexScore(), row.margin());
        return new ScoredExample(example, signals, scores);
    }

    private RoutingMetrics.Metrics metricsFor(
            List<ScoredExample> scored, EvalLanguage language, double simpleMargin) {
        List<RoutingMetrics.Prediction> predictions = new ArrayList<>();
        for (ScoredExample s : scored) {
            if (s.example().language() == language) {
                RoutingDecision decision = router.decisionEngine().decide(
                        RoutingStrategy.HYBRID, s.signals(), s.semanticScores(),
                        withSimpleMargin(simpleMargin));
                predictions.add(new RoutingMetrics.Prediction(
                        s.example().expectedTier(), decision.tier()));
            }
        }
        return RoutingMetrics.compute(predictions);
    }

    private RoutingMetrics.Metrics metricsForStrategy(
            List<ScoredExample> scored, RoutingStrategy strategy, double simpleMargin) {
        List<RoutingMetrics.Prediction> predictions = new ArrayList<>();
        for (ScoredExample s : scored) {
            RoutingDecision decision = router.decisionEngine().decide(
                    strategy, s.signals(), s.semanticScores(), withSimpleMargin(simpleMargin));
            predictions.add(new RoutingMetrics.Prediction(
                    s.example().expectedTier(), decision.tier()));
        }
        return RoutingMetrics.compute(predictions);
    }

    private SemanticRoutingProperties withSimpleMargin(double simpleMargin) {
        SemanticRoutingProperties base = properties.semantic();
        return new SemanticRoutingProperties(base.enabled(), base.model(), base.modelPath(),
                base.examples(), base.topKPerClass(), base.complexMargin(), simpleMargin);
    }

    private Map<String, Long> reasonDistribution(List<ScoredExample> scored, double simpleMargin) {
        Map<String, Long> counts = new TreeMap<>();
        for (ScoredExample s : scored) {
            RoutingDecision decision = router.decisionEngine().decide(
                    RoutingStrategy.HYBRID, s.signals(), s.semanticScores(),
                    withSimpleMargin(simpleMargin));
            RoutingReason reason = decision.reason();
            counts.put(reason.name(), counts.getOrDefault(reason.name(), 0L) + 1);
        }
        return counts;
    }

    private List<String> allTags(List<ScoredExample> scored) {
        return scored.stream()
                .flatMap(s -> s.example().tags().stream())
                .distinct().sorted().toList();
    }

    private List<RoutingMetrics.Prediction> predictionsForTag(
            List<ScoredExample> scored, String tag, double simpleMargin) {
        List<RoutingMetrics.Prediction> predictions = new ArrayList<>();
        for (ScoredExample s : scored) {
            if (s.example().tags().contains(tag)) {
                RoutingDecision decision = router.decisionEngine().decide(
                        RoutingStrategy.HYBRID, s.signals(), s.semanticScores(),
                        withSimpleMargin(simpleMargin));
                predictions.add(new RoutingMetrics.Prediction(
                        s.example().expectedTier(), decision.tier()));
            }
        }
        return predictions;
    }

    private static String summarize(RoutingMetrics.Metrics m) {
        return String.format(Locale.US,
                "acc=%.4f balAcc=%.4f macroF1=%.4f under=%d over=%d t2rate=%.4f",
                m.accuracy(), m.balancedAccuracy(), m.macroF1(),
                m.underRouted(), m.overRouted(), m.t2SelectionRate());
    }

    private static ObjectNode stats(double[] values) {
        ObjectNode node = JSON.createObjectNode();
        node.put("n", values.length);
        node.put("meanMs", mean(values));
        node.put("p50Ms", percentile(values, 50));
        node.put("p95Ms", percentile(values, 95));
        node.put("maxMs", max(values));
        return node;
    }

    private static double mean(double[] values) {
        double sum = 0.0;
        for (double v : values) {
            sum += v;
        }
        return sum / values.length;
    }

    private static double percentile(double[] values, double p) {
        double[] sorted = values.clone();
        java.util.Arrays.sort(sorted);
        int index = Math.min(sorted.length - 1,
                (int) Math.ceil(p / 100.0 * sorted.length) - 1);
        return sorted[Math.max(0, index)];
    }

    private static double max(double[] values) {
        double max = Double.NEGATIVE_INFINITY;
        for (double v : values) {
            max = Math.max(max, v);
        }
        return max;
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return hex(digest.digest());
    }

    private static String sha256Resource(String resource) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new DefaultResourceLoader().getResource("classpath:" + resource)
                .getInputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return hex(digest.digest());
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static String onnxRuntimeVersion() {
        try {
            String pom = Files.readString(Paths.get("pom.xml"));
            int index = pom.indexOf("onnxruntime");
            if (index != -1) {
                int versionTag = pom.indexOf("<version>", index);
                int endTag = pom.indexOf("</version>", versionTag);
                if (versionTag != -1 && endTag != -1) {
                    return pom.substring(versionTag + 9, endTag).trim();
                }
            }
        } catch (Exception ignored) {
            // fall through
        }
        return "unknown";
    }
}
