# VoxTicket — Final Evaluation Report (generated, not typed)

Every number below comes from a result JSON in the run directory. Anything missing is marked 'not measured' with its reason. No number in this report was typed in by hand, estimated silently, or taken from a mocked run.

Run date: 2026-11-04T15:38:09.6993241+05:00
Git commit: a78ff418243410536386f240f8476f9e63b4abea
Java: not found
Routing model path: D:\dev\VoxTicket-models\multilingual-e5-small
Embedding provider/model (record per run): local (Ollama bge-m3, pgvector)
Price table used for cost ESTIMATES: No price given: cost cells stay empty (not estimated).
RPM configured for API-calling scripts: 10

## E1 — Functional correctness
Status: unknown
Java: 1016 passed, 0 failed, 0 errors, 24 skipped, out of 1040 tests
Python: 100 passed, 0 failed, 0 errors, 0 skipped, out of 100 tests
Errors are infrastructure/context failures, never folded into passes. A skipped test is reported as skipped, never as passed.

## E2 — Security and safety
Status: unknown
probes: {'PASS': 6, 'DOCUMENTED': 4} (PASS = blocked as expected, DOCUMENTED = dev-only behaviour by design, FAIL = unexpected result, NEEDS_REVIEW = human must check the saved response)
Phone-assertion results are DOCUMENTED dev-only behaviour: the chat endpoint is permitAll by design and dev/test-profile gated, and it lets the caller assert a customer phone. That endpoint must never be exposed beyond dev/test. No probe mutated data; no procedure was confirmed.

## E3 — Adaptive routing
Offline routing suite: measured
Held-out final test, calibrated hybrid: accuracy 0.9083, balanced accuracy 0.9083, macro-F1 0.9076, over-routes 11, Tier 2 rate 0.5917
Routing-decision latency: structural: mean 0.0138 ms, p95 0.0281 ms; semantic: mean 10.6206 ms, p95 16.5086 ms
Tier selection from backend logs: not measured (no result file)
Live three-arm comparison (arms are DECLARED — chat responses carry no tier field; confirm arms from backend routing-decision logs):
- HYBRID: 14 turns, latency mean 1055.5 ms, median 889.3 ms, p95 2060.6 ms, estimated cost/call not measured (estimate only), task completion 0.8571, quality 3.86 (human scores only)
- ALWAYS_TIER_1: 14 turns, latency mean 1196.7 ms, median 1009.9 ms, p95 2922.4 ms, estimated cost/call not measured (estimate only), task completion 0.2857, quality 1.93 (human scores only)
- ALWAYS_TIER_2: 14 turns, latency mean 963.1 ms, median 872.4 ms, p95 2171.2 ms, estimated cost/call not measured (estimate only), task completion 0.5714, quality 2.79 (human scores only)

## E4 — RAG retrieval and answer quality
Status: measured
Retrieval: Recall@1 0.9118, Recall@3 0.9412, MRR 0.9265, nDCG@3 0.9303; unsupported handled 0/6
Answer quality is human scored only. No LLM judge was used as evidence. If the vector store was empty during the run, retrieval numbers are an infrastructure result, not a retrieval result, and are labelled so.

## E5 — Voice (user's manual calls)
Status: measured
10 calls; task completion 9/10; end-to-end mean 2806.8 ms, brain TTFT mean 2793.4 ms
Latency figures are copied from admin voice telemetry; scores are the user's human judgement. Latency aggregates are means of per-call means.

## Limitations (read before quoting any number)
- Groq free-tier rate limits: every 429 and backoff second is recorded in the per-area JSON files; a run with many 429s measured a throttled system, not a calm one.
- Live-turn sample sizes are small (state n wherever you quote a mean). No claim of statistical significance is made.
- Cost figures are word-count ESTIMATES (words x 1.33), not metered tokens, unless a metered source is attached.
- The three-arm comparison measures latency and human-judged quality; tier selection under HYBRID must come from backend logs, not from chat responses.
- Concurrency was not measured beyond single sessions in this package; provider-failure behaviour was not tested.
