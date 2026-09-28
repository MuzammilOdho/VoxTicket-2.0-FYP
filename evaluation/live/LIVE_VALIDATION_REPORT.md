# VoxTicket Live Validation Report — 2026-09-28

**Status: PARTIAL.** Automated, router, and evaluation layers fully validated with
evidence. All live application/provider/conversation/procedure testing is **BLOCKED**
by missing provider credentials in the environment (plus no PostgreSQL in the sandbox).

## 1. Environment

- Branch `converstaion`, HEAD `0bf8b0a0de4cbfee8aff70e3395c9a565019bc92`
- Working tree: dirty (uncommitted Phase 2 + Phase 3 work, 51 paths), `git diff --check` clean
- Java 21.0.12.1 (Temurin), Maven 3.9.16 (offline), Spring Boot 4.1.1, Spring AI 2.0.1, ONNX Runtime 1.22.0
- Docker: unavailable. Local PostgreSQL: unavailable (port 5432 closed, no pg binaries)

## 2. Credentials (presence only)

- `GEMINI_API_KEY`: absent
- `GROQ_API_KEY`: absent
- `CEREBRAS_API_KEY`: absent

Provider transport (no key sent, keyless GET): `generativelanguage.googleapis.com` → 404
(reachable), `api.groq.com` → 401, `api.cerebras.ai` → 403. Network path to all three
providers works; authentication is the missing piece.

Configured tiers: TIER_1 = GOOGLE `gemini-3.6-flash` (maxRetries=0), TIER_2 = GROQ
`openai/gpt-oss-20b` (maxRetries=0). The application fail-fasts at startup when a
configured tier's provider key is missing — by design, and this validation must not
weaken it. Therefore no live application run was possible.

## 3. Model artifacts (all match frozen metadata)

- `model.onnx` (470,268,510 bytes): `ca456c06…1cba6bc8665` ✓
- `tokenizer.json`: `0b44a9d7…4ebdf4c39` ✓
- `routing/examples-v1.json` (96 = 48/48 tiers): `235f3402…80e805564515e0154` ✓
- validation corpus (120): `7a264e2f…d599c46b4846` ✓
- final-test corpus (120): `4f549917…af82ed67c` ✓

## 4. Automated tests

`mvn clean test`: **346 run, 0 failures, 17 errors, 16 skipped, BUILD FAILURE.**
All 17 errors are the known Testcontainers "no valid Docker environment" failures —
identical to the Phase 1/2 baselines. Not green in this sandbox; the Docker-capable
run remains the user's gate. 0 failures across 329 executed tests.

Opt-in real-model suites (run explicitly): `E5TokenizerKnownIdsTest` 8/8,
`OnnxEmbeddingServiceTest` 7/7, `RoutingHybridContextTest` 1/1,
`Phase3EvaluationSuite` 5/5 — **21 run, 0 failures, 0 errors, BUILD SUCCESS.**

Skipped (16): opt-in model/tokenizer-gated tests and Docker-gated tests.

## 5. Phase 3 corpus evaluation (frozen, re-run 2026-09-28)

Calibration NOT re-run. Frozen `simpleMargin=0.01135858377758675` confirmed in
`frozen-threshold.json`; corpus hashes identical; all accuracies identical to the
frozen run. 43 candidates, objective = max balanced accuracy, tie-breaks = lower
under-routing → higher macro-F1 → lower TIER_2 rate.

Validation baseline (placeholder 0.02/0.02): acc 0.6917, balAcc 0.6917, macro-F1
0.6696, TIER_2 P/R/F1 0.6264/0.9500/0.7550, under 3, over 34, TIER_2 rate 0.7583.
Reasons: RULE_MULTI_ORDER_REFERENCE 8, SEMANTIC_SIMPLE 29, SEMANTIC_AMBIGUOUS 68,
SEMANTIC_COMPLEX 15. By language: URDU 0.8333, CODE_SWITCH 0.6667, ROMAN_URDU
0.6667, ENGLISH 0.6000.

Final held-out (frozen threshold):

| Strategy | Accuracy | Bal. acc. | Macro-F1 | Under | Over | TIER_2 rate |
|---|---|---|---|---|---|---|
| ALWAYS_TIER_1 | 0.5000 | 0.5000 | 0.3333 | 60 | 0 | 0.0000 |
| ALWAYS_TIER_2 | 0.5000 | 0.5000 | 0.3333 | 0 | 60 | 1.0000 |
| RULE_ONLY | 0.5667 | 0.5667 | 0.4665 | 52 | 0 | 0.0667 |
| HYBRID_CALIBRATED | 0.6500 | 0.6500 | 0.6465 | 15 | 27 | 0.6000 |

Constant-predictor macro-F1 uses the zero_division=0 convention (0.3333, not NaN).

HYBRID confusion (TIER_2 positive): TP 45, FP 27, TN 33, FN 15; precision 0.625,
recall 0.750, F1 0.682. Decision mix: 8 structural, 48 semantic-simple,
59 semantic-ambiguous, 5 semantic-complex.

HYBRID per language (n=30 each): ROMAN_URDU 0.7667 (under 1, over 6, T2 recall
0.9333, T2 usage 0.6667), URDU 0.6667 (5, 5, 0.6667, 0.5000), ENGLISH 0.6333
(4, 7, 0.7333, 0.6000), CODE_SWITCH **0.5333** (5, 9, 0.6667, 0.6333) — weakest,
not hidden.

## 6. Routing latency (real ONNX, re-run 2026-09-28)

100 warm-up + 300 measured iterations per path; every measured semantic iteration
ran genuine tokenization + ONNX inference + pooling + L2 normalization +
similarity scoring + `RoutingDecisionEngine.decide` (suite asserts 300 real
embedding calls, zero cache reuse; prototypes precomputed once).

- Structural: mean 0.1050 ms, p50 0.0102, p95 0.0414, max 24.79 (one outlier)
- Semantic: mean 13.71 ms, p50 9.59, p95 35.56, max 75.35
- One-off: model load 4637 ms, prototype index init 1884 ms

Per-turn semantic routing ≈ 10–15 ms — negligible against any LLM call.

## 7. Live application / providers / conversations — BLOCKED

Sections 7–25 of the validation plan (app startup, provider auth, live routing,
order tools, RAG/policy, cancellation/return/claim procedures, procedure state,
fabrication telemetry, security/adversarial, multilingual live, tool-call quality,
E2E latency, DB state, audit trail) could not run: no provider credentials in the
environment, and no PostgreSQL/pgvector in the sandbox. No live scenario was
attempted; no result is fabricated. The prior live evidence (2026-09-27 sandbox
run notes in `~/workspace/TOOLS.md`) is not re-validated here.

## 8. Failure classification

No live scenarios ran → no live failures to classify. Automated: 0 failures.

## 9. Key findings

1. Router + evaluation stack is fully reproducible: identical threshold, hashes,
   and metrics across three real-model runs.
2. Macro-F1 for constant predictors is now mathematically valid (0.3333).
3. Real per-turn semantic routing latency is ~10–15 ms (the earlier 0.06 ms
   measured the embedding cache).
4. Code-switch remains the weakest routing category (0.5333 final accuracy).
5. `mvn clean test` is 0-failure in the sandbox; the 17 Docker errors are
   environmental, not product failures.
6. All provider endpoints are network-reachable; credentials are the sole
   blocker for live validation.
7. The app's fail-fast-on-missing-key design was respected — no dummy-key
   startup was attempted.
8. No production file was modified during this validation (test/eval + docs only).
9. Live procedures, security, RAG, and multilingual conversation readiness are
   **not validated** — they need credentials + database.
10. Nothing was committed or pushed.

## 10. Artifacts

- `evaluation/routing/results/` — strategy-comparison, per-language, confusion-matrix-final,
  latency, baseline-validation, calibration, frozen-threshold, run-info, run-metadata (all CSV+JSON)
- `evaluation/live/results/provider-transport.json`
- `evaluation/live/results/run-metadata-live.json`
- `evaluation/live/LIVE_VALIDATION_REPORT.md` (this file)
