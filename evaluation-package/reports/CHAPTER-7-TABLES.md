# Chapter 7 — Evaluation tables (generated draft)

These tables are a draft for Chapter 7 only. Configuration values and results belong in Chapter 7 and nowhere else. Fill the .docx chapter from these tables, and keep every 'not measured' cell honest.

## Table 7.1 — Routing accuracy (offline suite, held-out final test)

| Strategy | Accuracy | Balanced accuracy | Macro-F1 | Over-routes | Tier 2 rate |
|---|---|---|---|---|---|
| FINAL_TEST ALWAYS_TIER_1 | 0.5000 | 0.5000 | 0.3333 | 0 | 0.0000 |
| FINAL_TEST ALWAYS_TIER_2 | 0.5000 | 0.5000 | 0.3333 | 60 | 1.0000 |
| FINAL_TEST RULE_ONLY | 0.5000 | 0.5000 | 0.3333 | 0 | 0.0000 |
| FINAL_TEST HYBRID_CALIBRATED | 0.9083 | 0.9083 | 0.9076 | 11 | 0.5917 |

## Table 7.2 — Routing-decision latency (ms)

| Path | Mean | p50 | p95 | Max |
|---|---|---|---|---|
| structural | 0.0138 | 0.0103 | 0.0281 | 0.161 |
| semantic | 10.6206 | 9.626 | 16.5086 | 22.8952 |

## Table 7.3 — Live three-arm comparison (identical scenarios)

| Arm | Turns | Latency mean (ms) | Latency p95 (ms) | Est. cost/call (USD, estimate) | Task completion (human) | Quality mean (human, 1-5) |
|---|---|---|---|---|---|---|
| HYBRID (declared) | 14 | 1055.5 | 2060.6 | not measured | 0.8571 | 3.86 |
| ALWAYS_TIER_1 (declared) | 14 | 1196.7 | 2922.4 | not measured | 0.2857 | 1.93 |
| ALWAYS_TIER_2 (declared) | 14 | 963.1 | 2171.2 | not measured | 0.5714 | 2.79 |

## Table 7.4 — RAG retrieval metrics

| Metric | Value |
|---|---|
| Recall@1 | 0.9118 |
| Recall@3 | 0.9412 |
| MRR | 0.9265 |
| nDCG@3 | 0.9303 |
| Unsupported handled correctly | 0/6 |

## Table 7.5 — Security and functional summary

| Area | Result |
|---|---|
| E1 Java | 1016 passed, 0 failed, 0 errors, 24 skipped, out of 1040 tests |
| E1 Python | 100 passed, 0 failed, 0 errors, 0 skipped, out of 100 tests |
| E2 live probes | probes: {'PASS': 6, 'DOCUMENTED': 4} (PASS = blocked as expected, DOCUMENTED = dev-only behaviour by design, FAIL = unexpected result, NEEDS_REVIEW = human must check the saved response) |
| E5 voice | 10 calls; task completion 9/10; end-to-end mean 2806.8 ms, brain TTFT mean 2793.4 ms |
