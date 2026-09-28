# Phase 3 — Multilingual Routing Evaluation and Calibration

**Branch:** `converstaion` · **Run:** 2026-09-27T22:07:26Z · **Git SHA:** `0bf8b0a0de4cbfee8aff70e3395c9a565019bc92`
**Model:** `intfloat/multilingual-e5-small` (384 dims, ONNX Runtime 1.22.0, real `model.onnx`)
**No changes were pushed to GitHub.**

This report documents the end-to-end Phase 3 evaluation of the Phase 2 lightweight
multilingual semantic tier router. The router decides **only** `TIER_1` / `TIER_2`
(never provider, model, authorization, ownership, OTP, or confirmation).

---

## 1. Routing decision semantics (audit of production code)

`RoutingDecisionEngine` with the HYBRID strategy behaves exactly as follows:

- A structural signal (e.g. ≥2 distinct `ORD-*` references) short-circuits to a
  `RULE_*` decision — no embedding is spent.
- Otherwise one query embedding is scored against the 96 prototypes (`topK=3` per
  class), producing `margin = complexScore − simpleScore`:
  - `margin ≥ complexMargin` → `TIER_2` / `SEMANTIC_COMPLEX`
  - `margin ≤ −simpleMargin` → `TIER_1` / `SEMANTIC_SIMPLE`
  - otherwise → `TIER_2` / `SEMANTIC_AMBIGUOUS`

**Audit finding:** only `simpleMargin` moves the TIER_1/TIER_2 boundary.
`complexMargin` merely renames the TIER_2 reason (`SEMANTIC_COMPLEX` vs
`SEMANTIC_AMBIGUOUS`) and is therefore **not identifiable from binary tier
labels** — it cannot be calibrated and stays at `0.02` (pinned by
`ThresholdCalibratorTest.complexMarginDoesNotAffectTierSelection`).
`topK=3` is unchanged. No architecture change was made in this phase.

## 2. Evaluation corpus

- `evaluation/routing/corpus/RUBRIC.md` — labeling rules, language definitions
  (English / Urdu script / Roman Urdu / code-switch), and per-tier exemplars.
- `evaluation/routing/corpus/validation.json` — 120 examples (calibration set).
- `evaluation/routing/corpus/final-test.json` — 120 examples (held-out set,
  evaluated exactly once).

Each file: 30 examples per language × 15 TIER_1 + 15 TIER_2 per language.
IDs encode split/language/tier (`VAL-EN-T1-001`, `TST-RU-T2-042`, …). Every
example carries `id, split, language, text, expectedTier, tags` (22-tag controlled
vocabulary, enforced in code) and `sourceNote`.

**Integrity & leakage checks** (`EvalCorpusIntegrityTest`, 8/8 green):
- exact sizes 120/120; 30/15/15 balance per split/language/tier;
- no duplicate IDs; no duplicate normalized text;
- **no text overlap between VALIDATION and FINAL_TEST**;
- **no overlap with the 96 routing prototypes** (`examples-v1.json` untouched);
- no blanks; IDs match their split/language/tier; tags ⊆ controlled vocabulary.

**Semantic near-duplicate audit** (real ONNX embeddings, max cosine similarity of
each of the 240 examples to its nearest prototype):
- 0 examples ≥ 0.95 (suspicious) → **no replacements made**;
- 56 examples in [0.90, 0.95) — manual review showed these are same-topic pairs
  (e.g. a TIER_2 ambiguous correction near a TIER_2 correction prototype, or two
  independent simple cancellations), not paraphrase copies.
- Results: `results/near-duplicate-audit.csv`.

## 3. Validation baseline (frozen Phase 2 defaults: simpleMargin=0.02, complexMargin=0.02, topK=3)

| Metric | Value |
|---|---|
| Accuracy / balanced accuracy | 0.6917 / 0.6917 |
| Macro-F1 | 0.6696 |
| TIER_2 precision / recall / F1 | 0.6264 / 0.9500 / 0.7550 |
| Under-routed (true TIER_2 → TIER_1) | 3 |
| Over-routed (true TIER_1 → TIER_2) | 34 |
| TIER_2 selection rate | 0.7583 |
| Structural-rule coverage | 0.0667 (8/120) |
| Semantic-simple / complex / ambiguous | 29 / 15 / 68 |

Per-language accuracy (baseline): Urdu 0.8333 · English 0.6000 · Roman Urdu 0.6667 ·
code-switch 0.6667.

**Reading:** at the 0.02 placeholder the router strongly over-routes: TIER_2 recall
is 0.95 but simple intents (`single-lookup` 0.125, `order-status` 0.438,
`return-request` 0.417, `greeting` 0.50 per-tag accuracy) are pushed to TIER_2 by
the narrow ambiguous band, while genuinely complex tags (`comparison`,
`conditional`, `correction`, `multi-intent`) score 1.000. The semantic scorer is
biased toward TIER_2 on this corpus — exactly what calibration should fix.

## 4. Calibration (validation set only)

- 43 candidate `simpleMargin` values, derived from observed validation margins
  (each candidate is a margin or a midpoint between adjacent distinct margins;
  all ≥ 0). Zero additional inference: query scores were cached once.
- Objective: maximize **balanced accuracy**; tie-breaks: lower under-routing →
  higher macro-F1 → lower TIER_2 selection rate.
- **Selected: simpleMargin = 0.01135858377758675** (won on the first tie-break:
  under=9 vs 10 at equal balanced accuracy 0.7583).

| Metric | Before (0.02) | After (0.01136) |
|---|---|---|
| Balanced accuracy | 0.6917 | **0.7583** |
| Macro-F1 | 0.6696 | 0.7563 |
| Under-routed | 3 | 9 |
| Over-routed | 34 | 20 |
| TIER_2 selection rate | 0.7583 | 0.5917 |

The wider TIER_1 band trades 6 extra under-routes for 14 fewer over-routes.
Full sweep: `results/calibration.csv` (+ `.json`).

**Hard guard:** `ThresholdCalibrator.calibrate` rejects any FINAL_TEST example with
`IllegalArgumentException`, so the held-out set cannot influence calibration
(regression test: `ThresholdCalibratorTest.rejectsFinalTestExamples`).

## 5. Threshold freeze

- The selected value is recorded in `results/frozen-threshold.json`.
- Production defaults updated (annotation default, `RoutingProperties` compact
  constructor, `application.yaml`): `simple-margin: 0.01135858377758675`;
  `complex-margin` stays `0.02` with a comment explaining why it is not
  calibrated; `topK=3` unchanged.
- Pinned by `CalibratedRoutingDefaultsTest` (frozen value + boundary behavior).
- The threshold was **not** re-tuned after the final test.

## 6. Final held-out evaluation (frozen threshold, run exactly once)

| Strategy | Accuracy | Bal. acc. | Macro-F1 | Under | Over | TIER_2 rate |
|---|---|---|---|---|---|---|
| ALWAYS_TIER_1 | 0.5000 | 0.5000 | 0.3333 | 60 | 0 | 0.0000 |
| ALWAYS_TIER_2 | 0.5000 | 0.5000 | 0.3333 | 0 | 60 | 1.0000 |
| RULE_ONLY | 0.5667 | 0.5667 | 0.4665 | 52 | 0 | 0.0667 |
| **HYBRID_CALIBRATED** | **0.6500** | **0.6500** | **0.6465** | **15** | **27** | **0.6000** |

HYBRID_CALIBRATED beats RULE_ONLY by **+8.3 pp accuracy** (0.6500 vs 0.5667) and
cuts under-routing from 52 → 15 while keeping the TIER_2 selection rate at 0.60
(vs 1.00 for ALWAYS_TIER_2).

- Confusion matrix (HYBRID, TIER_2 positive): tp=45, fp=27, tn=33, fn=15 →
  TIER_2 precision 0.625, recall 0.750, F1 0.682.
- Decision mix: 8 structural (`RULE_MULTI_ORDER_REFERENCE`), 112 semantic —
  `SEMANTIC_SIMPLE` 48, `SEMANTIC_AMBIGUOUS` 59, `SEMANTIC_COMPLEX` 5.
  (The narrow 0.02 complex band above the calibrated simple margin leaves most
  TIER_2 semantic decisions labeled AMBIGUOUS — a labeling artifact, not a tier
  error, per the §1 audit.)

Per-language accuracy (HYBRID_CALIBRATED, n=30 each): Roman Urdu 0.7667 ·
Urdu 0.6667 · English 0.6333 · **code-switch 0.5333** (under=5, over=9).
Code-switch is materially worse than the other three languages and is the
clearest headroom for future prototype/threshold work. Full detail:
`results/per-language.csv`, `results/hybrid-final-detail.json`,
`results/final-test-detail.csv` (per-example), `results/confusion-matrix-final.json`.

## 7. Latency experiment (real ONNX model)

100 semantic warm-up iterations; 300 measured iterations per path, round-robin
over all validation texts taking that path. Every measured semantic iteration
runs genuine query tokenization + ONNX inference + pooling + normalization +
similarity scoring against the precomputed prototypes +
`RoutingDecisionEngine.decide` — the suite asserts 300 real embedding calls
(zero cache reuse) and zero new prototype inference during the latency index
build. (The pre-re-audit numbers measured the query-embedding cache instead of
real inference and are superseded.)

| Path | mean | p50 | p95 | max |
|---|---|---|---|---|
| Structural (regex/extraction only) | 0.0310 ms | 0.0105 ms | 0.0436 ms | 2.2703 ms |
| Semantic (tokenize + ONNX + topK scoring + decide) | 15.2715 ms | 10.8707 ms | 36.0249 ms | 93.3412 ms |

One-off startup costs (cold JVM): model load 5695 ms, prototype index build
(96 embeddings) 3098 ms. Per-turn semantic routing adds ~15 ms — still
negligible against any LLM call. Raw data: `results/latency.csv` (+ `.json`).

## 8. Provider-backed E2E (opt-in)

`Phase3ProviderE2E`: **NOT_VALIDLY_EXECUTABLE**. Preconditions checked:
- `GEMINI_API_KEY`, `GROQ_API_KEY`, `CEREBRAS_API_KEY` — all absent in this
  environment;
- the last known live state (2026-09-26) was Google Tier 1
  **HTTP 429 free-tier quota exceeded**.

No provider call was attempted; nothing is reported as a router result. The mode
remains available for a future run where credentials and quota are valid.

## 9. Re-audit corrections (2026-09-28)

Two methodology fixes were applied and all affected artifacts regenerated by
re-running the real-model suite with everything frozen (threshold
0.01135858377758675, corpus, labels, router logic, topK=3, final-test set):

1. **Macro-F1 for constant predictors** — `RoutingMetrics` now uses the
   zero_division=0 convention (0.0 instead of NaN for zero-denominator ratios),
   so ALWAYS_TIER_1/ALWAYS_TIER_2 report the mathematically valid macro-F1 of
   0.3333 on balanced classes. All other strategy metrics are unchanged
   (verified identical to the original run).
2. **Latency benchmark** — the original semantic numbers measured the
   query-embedding cache (0.06 ms mean), not real inference. The benchmark now
   runs genuine tokenization + ONNX inference + pooling + normalization +
   similarity scoring + `RoutingDecisionEngine.decide` per measured iteration
   (asserted: 300 real embedding calls, zero cache reuse; prototypes
   precomputed once and reused, asserted zero new prototype inference).

## 10. Reproducibility artifacts

```
evaluation/routing/
  RUBRIC.md                      corpus/validation.json, corpus/final-test.json
  README.md                      (re-run instructions)
  PHASE3_REPORT.md               (this file)
  results/
    run-metadata.json            run provenance + all hashes
    baseline-validation.json     baseline metrics (overall/by-language/by-tag/reasons)
    calibration.csv / .json      all 43 candidates
    frozen-threshold.json        selected margin + objective
    strategy-comparison.csv/.json  4 strategies on FINAL_TEST
    per-language.csv / .json     HYBRID per-language on FINAL_TEST
    hybrid-final-detail.json     reasons, structural/semantic mix
    confusion-matrix-final.json  tp/fp/tn/fn
    final-test-detail.csv / .json  per-example decisions
    latency.csv / .json          benchmark
    near-duplicate-audit.csv     240-example prototype similarity audit
```

SHA-256: ONNX `ca456c06…1cba6bc8665` · tokenizer `0b44a9d7…4ebdf4c39` ·
prototypes `235f3402…80e805564515e0154` · validation `7a264e2f…d599c46b4846` ·
final-test `4f549917…af82ed67c` (post schema key-rename; values unchanged).

Harness: `src/test/java/com/voxticket/routing/eval/` reuses production
`StructuralFeatureExtractor`, `SemanticRoutingService`, `RoutingDecisionEngine`,
`RoutingExampleIndex`, and checks parity against `ModelSelector`
(`RouterParityTest`, 3/3). Focused tests: corpus integrity (8), metrics (4),
calibrator incl. FINAL_TEST guard (7), parity (3), calibrated-default (2),
architecture boundary (6) — **30/30 green**.

## 11. Limitations

- The corpus is synthetic and author-labeled (single author); labels encode the
  rubric's judgment, not production traffic. 120 examples per split is small —
  per-language n=30 gives wide confidence intervals.
- No production traffic validation; routing quality on real conversations may
  differ (especially code-switch, the weakest language here).
- `complexMargin` intentionally uncalibrated (not identifiable from tier labels).
- Latency measured on the sandbox CPU; production hardware will differ.
- Provider-backed E2E was not validly executable (no credentials/quota).

## 12. Full test suite

`mvn clean test`: **343 run, 0 failures, 17 errors, 16 skipped.**
All 17 errors are the known sandbox limitation — Testcontainers
("Could not find a valid Docker environment"), the identical 17 as the Phase 2
baseline, unrelated to Phase 3. All 326 non-Docker tests pass (0 failures).

Per the standing rule this is reported as-is, not claimed green: a Docker-capable
run is still the final gate.

## 13. Independent re-run (2026-09-28, fresh session)

The full Phase 3 pipeline was re-executed from scratch in a new session
(frozen corpus, frozen harness, no reliance on prior session state):

```bash
mvn -o -Dtest='Phase3EvaluationSuite' -Dvoxticket.phase3.eval=true \
  -Dvoxticket.routing.model.path=/home/hatch/workspace/models/e5 \
  -Dvoxticket.phase3.git-sha=0bf8b0a0de4cbfee8aff70e3395c9a565019bc92 test
```

Result: **bit-identical reproduction** of every reported number — validation
baseline (acc 0.6917, balAcc 0.6917, macroF1 0.6696, under 3, over 34),
calibration selection (simpleMargin = 0.01135858377758675; after: balAcc 0.7583,
macroF1 0.7563, under 9, over 20), final-test strategy comparison
(HYBRID_CALIBRATED 0.6500/0.6500/0.6465), per-language table, confusion matrix
(TP 45 / FP 27 / TN 33 / FN 15), and reason mix (8 structural, 48
SEMANTIC_SIMPLE, 59 SEMANTIC_AMBIGUOUS, 5 SEMANTIC_COMPLEX). Corpus hashes
unchanged (validation `7a264e2f…`, final-test `4f549917…`); model/tokenizer/
prototype SHAs re-verified. Fresh `run-info.json` written (2026-09-28T16:07:48Z).
Latency re-measured on the same sandbox CPU: structural mean 0.051/p50 0.013/
p95 0.044/max 4.23 ms; semantic mean 14.68/p50 9.94/p95 38.76/max 78.18 ms
(300 measured iterations, real ONNX inference each, zero query-embedding reuse).

No corpus, prototype, threshold, or routing-logic change was made for this
re-run; the only tree changes are additive (`.gitignore` now ignores `*.onnx`
and `models/`; `scripts/` PowerShell live-environment scripts; this addendum).

Full `mvn clean test` in the same session: **346 run, 0 failures, 17 errors,
16 skipped.** All 17 errors are the known sandbox limitation (Testcontainers:
"Could not find a valid Docker environment"); 329/329 non-Docker tests pass.
Per the standing rule this is reported as-is, not claimed green — a
Docker-capable run remains the final gate.
