# Phase 3 evaluation — re-run guide

Multilingual routing evaluation and calibration for the VoxTicket semantic tier
router. All commands run from the repository root (`~/workspace/voxticket`) with
the sandbox Maven setup:

```bash
export JAVA_HOME=~/workspace/tools/jdk-21.0.12.1+1
MVN="bash mvnw -B -o -Dmaven.repo.local=/home/hatch/.m2/repository"
```

## Corpus

- `corpus/RUBRIC.md` — labeling rules and exemplars.
- `corpus/validation.json` — 120 examples, calibration only.
- `corpus/final-test.json` — 120 examples, held-out; evaluate **exactly once**,
  never use for tuning.

Do not modify `src/main/resources/routing/examples-v1.json` (routing prototypes)
and do not use evaluation examples as routing prototypes.

## Focused (hermetic, no model needed)

```bash
$MVN surefire:test \
  -Dtest='EvalCorpusIntegrityTest,RoutingMetricsTest,ThresholdCalibratorTest,RouterParityTest,CalibratedRoutingDefaultsTest,RoutingArchitectureBoundaryTest'
```

Covers corpus integrity/leakage, metrics math, threshold search/selection
(including the FINAL_TEST rejection guard), harness-vs-`ModelSelector` parity,
the frozen calibrated default, and routing architecture boundaries.

## Semantic near-duplicate audit (needs the real ONNX model)

```bash
$MVN surefire:test -Dtest='Phase3NearDuplicateAudit' \
  -Dvoxticket.phase3.audit=true \
  -Dvoxticket.routing.model.path=/path/to/e5
```

Reports max cosine similarity of each of the 240 evaluation examples to its
nearest routing prototype. Replace any example ≥ 0.95 (suspicious) **before**
running the evaluation suite. Writes `results/near-duplicate-audit.csv`.

## Full evaluation pipeline (needs the real ONNX model)

```bash
$MVN surefire:test -Dtest='Phase3EvaluationSuite' \
  -Dvoxticket.phase3.eval=true \
  -Dvoxticket.routing.model.path=/path/to/e5 \
  -Dvoxticket.phase3.git-sha=$(git rev-parse HEAD)
```

Runs, in order: inference on both splits (scores cached once) → validation
baseline → calibration on VALIDATION only → threshold freeze → FINAL_TEST
exactly once (ALWAYS_TIER_1, ALWAYS_TIER_2, RULE_ONLY, HYBRID_CALIBRATED) →
latency benchmark. Writes all `results/*.csv` / `results/*.json` artifacts.

## Provider-backed E2E (opt-in, needs credentials + quota)

```bash
$MVN surefire:test -Dtest='Phase3ProviderE2E' \
  -Dvoxticket.phase3.provider-e2e=true
```

Reports `NOT_VALIDLY_EXECUTABLE` unless provider credentials are present and
usable; quota failures are reported as environment blockers, never as router
failures.

## Full test suite

```bash
$MVN clean test
```

Sandbox note: the 17 Testcontainers tests error without a Docker daemon (known
environment limitation); everything else must pass with 0 failures.
