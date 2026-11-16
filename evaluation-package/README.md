# VoxTicket — Complete Automated Evaluation Package (v9 FINAL, synced to main a78ff41)

Extract at the repository root, then run one script. Everything that can
be automated is automated; everything that cannot is marked honestly
instead of faked.

Synced to main `a78ff41` after the intentional evaluation restart of
2026-11-03. The 2026-11-01 evaluation commits were deliberately abandoned
and this package does not depend on them. Versions 1–8 are superseded:
do not use them. This is the final package. v9 fixes one v8 gap: plain
`mvn clean test` does not pass the routing model path, so without env
`ROUTING_MODEL_PATH` every Spring-context test errors with "Routing
model directory is not configured" (140 errors on the 2026-11-03 run).
The runner now defaults that variable from the resolved model path when
the user has not set it; it never overrides an explicit value. This
also fixes the E4 harness, which boots the same context. No other
change vs v8. On this baseline, the production simple-margin default
is the pre-restart calibration; the routing suite re-calibrates on every
run, so E3 numbers stay valid, and applying any new calibration to
production defaults is a separate user decision, not part of this package.

## Quick start (v9 FINAL)

1. Extract this zip at the repository root. It places six repo files
   (review them with `git diff` before running — the runner itself never
   modifies your repo):
   - `java-backend/src/main/java/com/voxticket/persistence/seed/SeedDatasetFactory.java`
     — anchor moved 2026-09-01 → 2026-10-24 (the only change, two lines).
   - `java-backend/src/test/java/com/voxticket/persistence/seed/SeedDatasetDeterminismTest.java`
     — the matching comment (one line).
   - `java-backend/src/test/java/com/voxticket/rag/RagEvaluationHarnessManualTest.java`
     — the gated E4 harness test (new file, property-gated only — no
     `@Tag("manual")`, because the pom hard-codes
     `<excludedGroups>manual</excludedGroups>` as a literal that CLI
     flags cannot override, which made the v4 invocation silently run
     0 tests; without the property, JUnit disables it, so a plain
     gate stays green).
   - `java-backend/evaluation/routing/corpus/validation.json` and
     `final-test.json` — the 120+120 routing corpus from 2026-11-01
     (unchanged; the re-anchor never touched the corpus), so the suite
     runs even on a fresh tree that never had them.
   - `java-backend/evaluation/README.md` — the suite runbook.
2. Wipe + reseed the database once (the seeder skips seeding when
   customers already exist), then verify ORD-10006 is return-eligible,
   ORD-10007 is expired, and ORD-10003 is present and cancellable.
   The anchor keeps ORD-10006 eligible only through 2026-11-13
   (delivered 2026-10-14 + 30 days). After that date, re-anchor again:
   anchor = run date minus 10 days, then wipe + reseed again.
3. Run the script with YOUR OWN backend (see "Self-run backend" below
   for the full sequence). The `-AutoBackend` option still exists, but
   you do not need it: a backend that is already running is used as-is
   and never stopped or restarted by the script. The model path is
   auto-resolved from `VOXTICKET_ROUTING_MODEL_PATH` or
   `D:\dev\VoxTicket-models\multilingual-e5-small`; pass `-ModelPath`
   only if yours lives elsewhere.
4. Later, after the 10 manual voice calls: drop the filled template as
   `evaluation-package/voice_calls_filled.csv` and re-run the script
   (it picks it up automatically), or pass `-VoiceCsv`.

## Self-run backend (you run the backend yourself)

Do NOT use `-AutoBackend`. The live arms each need the backend running
with that arm's strategy, so you restart it yourself between arms. The
script never stops your backend — it only talks to whatever is already
on port 8080. Copy the results-folder path the script prints at the
start (it looks like `results\2026-11-03-XXXX`) and use it in steps 2–4.

1. Start the backend in IntelliJ with the dev profile
   (`-Dspring.profiles.active=dev`), keys in your run configuration,
   database up and reseeded. The default strategy is HYBRID, so no
   extra setting is needed for this arm. Then run the full script for
   the HYBRID arm only — the `-LiveArms "HYBRID"` matters: without it,
   the script would run all three arms against this one backend and
   mislabel two of them:

   ```powershell
   cd D:\dev\VoxTicket-2.0-FYP\evaluation-package
   .\Run-Evaluation.ps1 -LiveArms "HYBRID"
   ```

   This measures E1, E2, the routing suite, E4, and the HYBRID live
   arm, and writes the reports.
2. Restart the backend with `VOXTICKET_AI_SELECTOR_STRATEGY=ALWAYS_TIER_1`
   in the IntelliJ run configuration's environment variables, then run
   just that arm's live turns into the SAME results folder:

   ```powershell
   python scripts/e3_live.py --base-url http://localhost:8080 --scenarios datasets/e3_scenarios.json --arm ALWAYS_TIER_1 --out results\<timestamp>\e3_live_ALWAYS_TIER_1.json --rpm 10
   ```

3. Restart the backend with `VOXTICKET_AI_SELECTOR_STRATEGY=ALWAYS_TIER_2`
   and run that arm the same way:

   ```powershell
   python scripts/e3_live.py --base-url http://localhost:8080 --scenarios datasets/e3_scenarios.json --arm ALWAYS_TIER_2 --out results\<timestamp>\e3_live_ALWAYS_TIER_2.json --rpm 10
   ```

   The arm is DECLARED in every file (chat responses carry no tier
   field). Confirm each arm from your backend log lines
   `event=routing_decision ... strategy=...` for that run window, or
   run `scripts/e3_log_parse.py` over a saved backend log.
4. Combine the three arms and regenerate the reports from that same
   results folder:

   ```powershell
   python scripts/e3_aggregate.py --arm-files results\<timestamp>\e3_live_ALWAYS_TIER_1.json results\<timestamp>\e3_live_ALWAYS_TIER_2.json results\<timestamp>\e3_live_HYBRID.json --out results\<timestamp>\e3_comparison.json
   python scripts/make_report.py --results-dir results\<timestamp> --meta results\<timestamp>\meta.json --out-md reports\FINAL-EVALUATION-REPORT.md --out-ch7 reports\CHAPTER-7-TABLES.md
   ```

5. Optional but needed for quality/completion numbers: each live run
   writes a `*_human_scores_template.csv` next to its result file.
   Fill in `task_completed` and `quality_1_to_5` per turn, then
   re-run step 4's aggregator with all three filled files added as
   `--human-scores <file1> <file2> <file3>`, then the report command
   again. Without human scores, quality and completion stay
   "not measured" — no LLM judge is used, by design.

## What this package is (and is not)

It runs E1–E4 automatically and prepares E5 for your manual calls. It does
NOT automate voice calls, it does NOT reseed your database, it does NOT
push anything to GitHub, and it stores NO credentials anywhere. Every
number in the generated reports comes from a result JSON file; anything
missing is written as "not measured" with its reason.

Two earlier failures are designed out here on purpose:

- The old scripts hardcoded results (1027/1033 baked into a script that
  "parsed" output with a pattern that cannot match surefire output). This
  package computes every number from XML, logs, or API responses.
- The old E2 report claimed 401 for unauthenticated chat. That is wrong:
  `/api/v1/chat` is `permitAll` by design (dev/test-profile gated) in
  `AdminSecurityConfig`. This package's probes encode the real rules, and
  phone assertion is reported as documented dev-only behaviour, not as a
  blocked attack.

## Layout

- `Run-Evaluation.ps1` — the master runner (Windows PowerShell).
- `scripts/` — one Python script per job, standard library only.
- `datasets/` — the corrected RAG dataset (v2), the E3 scenarios, the E2
  probe definitions. The RAG dataset is valid JSON, carries the `type`
  field the harness requires, maps every question to a policy-document
  stem, and covers English, Urdu, and Roman Urdu.
- `templates/voice_calls_template.csv` — the 10-call voice template.
- Plus the six repo files listed in Quick start, placed at their real
  repo paths by the zip (not inside `evaluation-package/`).
- `results/<timestamp>/` — created per run (raw JSONs, logs, CSVs).
- `reports/` — created per run (FINAL-EVALUATION-REPORT.md,
  CHAPTER-7-TABLES.md). The Chapter 7 file is a generated draft for the
  tables only; the .docx chapter itself is still updated by hand, last,
  from these tables.

## Prerequisites

- Repository checked out at main `a78ff41`, this zip extracted at the
  repository root (so the script sits at
  `<repo>/evaluation-package/Run-Evaluation.ps1` and the six repo
  files land at their real paths). Review the seed/harness/corpus
  changes with `git diff` and `git status` before running.
- Java 21 + Maven, Python 3 (for the scripts), `git` on PATH.
- For E3 routing: the ONNX model folder with `model.onnx` +
  `tokenizer.json` (pass it with `-ModelPath`).
- For E2 probes and E3 live turns: the backend running with the dev
  profile (`-Dspring.profiles.active=dev`), keys in your IntelliJ run
  configuration / environment as usual.
- For E4: a dev PostgreSQL database with the vector store populated with
  policy embeddings. If it is empty, E4 honestly reports "not measured"
  instead of zeros.

## Run it

```powershell
# From the evaluation-package folder, or anywhere with -RepoRoot.
# Self-run backend (you run it in IntelliJ): full run, HYBRID arm only.
# See "Self-run backend" above for the other two arms.
.\Run-Evaluation.ps1 -LiveArms "HYBRID"

# Fully hands-off alternative (only if you want it): the script builds
# the jar and starts the backend per arm. Needs keys in this session
# and a free port 8080.
.\Run-Evaluation.ps1 -AutoBackend
```

That runs E1, E2 probes, the routing suite, the E4 dataset check +
harness + metrics, and the live arm(s) you asked for. The model path is
auto-resolved (see Quick start); pass `-ModelPath` only if yours lives
elsewhere. The customer phone is auto-resolved from the demo endpoint;
pass `-CustomerPhone` only to force a specific one. Useful options:

```powershell
# Live arms only, or one arm: the arm is DECLARED — chat responses carry
# no tier field, so confirm the arm from backend log lines
# 'event=routing_decision ... strategy=...' for the run window, or run
# scripts/e3_log_parse.py over a saved backend log.
.\Run-Evaluation.ps1 -LiveArms "HYBRID"
.\Run-Evaluation.ps1 -LiveArms "ALWAYS_TIER_1,ALWAYS_TIER_2,HYBRID"

# Skip all live work (offline only: E1, routing suite, E4 dataset check):
.\Run-Evaluation.ps1 -SkipLive

# Cost estimates (word-count estimates, NOT metered tokens). Current main
# prices all tiers at 0.0 (excluded), so only pass a price after verifying
# it against Groq's published pricing page; never quote an unverified price.
.\Run-Evaluation.ps1 -AutoBackend -PricePer1k 0.0003

# After the 10 manual voice calls: drop the filled template as
# evaluation-package/voice_calls_filled.csv and re-run, or pass it:
.\Run-Evaluation.ps1 -VoiceCsv ".\voice_calls_filled.csv"
```

## RPM (rate limits) — read this before the live runs

Groq already returned 429 errors at single-session load in earlier runs,
so every API-calling script paces itself: default **10 requests/minute**,
with backoff on 429 (30s, 60s, 120s, 240s, honouring the server's
`Retry-After` header when present) and up to 5 retries. Every 429 and
every backoff second is recorded in the output JSON — a run with many
429s measured a throttled system, and the report says so. Raise `-Rpm`
only if your key's plan allows it. E3 live runs are resumable: re-running
with the same output file skips already-recorded scenarios instead of
starting over, so a rate-limit interruption costs nothing.

The E4 harness has no retry loop of its own: a failed retrieval run is
reported as failed, not retried into a pass. (The discarded 2026-11-01
local variant had a Voyage retry; the gated harness in this package
deliberately does not.)

## The 10 manual voice calls (E5) — your part

0. **Reseed once (the re-anchor is already applied by this zip).**
   Before the calls: wipe the database (the seeder skips seeding when
   customers already exist), reseed, then verify ORD-10006 is
   return-eligible, ORD-10007 is expired, and ORD-10003 is present and
   cancellable. The anchor in this zip (2026-10-24) keeps ORD-10006
   eligible only through 2026-11-13 (delivered 2026-10-14 + 30 days).
   After that date, re-anchor again: anchor = run date minus 10 days,
   then wipe + reseed again. Record the anchor you used in the run header.
1. Make sure the database is seeded as in step 0. If you reseeded
   differently, update the order column in the template.
2. Do the 10 calls in `templates/voice_calls_template.csv` (order status
   in English and Roman Urdu, return eligibility eligible + expired,
   cancellation with OTP twice, damaged-item claim, code-switched complex
   query, multi-issue with interruption, full Urdu-script conversation).
3. For each call, copy the latency figures from the admin voice telemetry
   (STT, brain TTFT, TTS first audio, end-to-end, aborted, barge-in) into
   the template, mark task completion (yes/no/partial), and score the five
   human dimensions 1–5. If Groq 429s interrupt a call, write it in
   `error_notes` — it is data, not a failure to hide.
4. Run the package with `-VoiceCsv` pointing at the filled template, or
   run just the processor:
   `python scripts/e5_process.py --csv <filled.csv> --out-json results/e5.json --out-md results/e5_table.md`
5. Or send me the filled template and I will compute E5 for you.

## Human scoring (E3 quality, E4 answers)

Automation never scores answer quality. After a live run, each arm writes
a `*_human_scores_template.csv` next to its result file. Fill in
`task_completed` and `quality_1_to_5` per turn, then re-run the
aggregator with all filled files:

```powershell
python scripts/e3_aggregate.py --arm-files results/e3_live_ALWAYS_TIER_1.json results/e3_live_ALWAYS_TIER_2.json results/e3_live_HYBRID.json --human-scores results/e3_live_ALWAYS_TIER_1_human_scores.csv results/e3_live_ALWAYS_TIER_2_human_scores.csv results/e3_live_HYBRID_human_scores.csv --out results/e3_comparison.json
python scripts/make_report.py --results-dir results --meta results/meta.json --out-md reports/FINAL-EVALUATION-REPORT.md --out-ch7 reports/CHAPTER-7-TABLES.md
```

Without human scores, quality and completion stay "not measured". No LLM
judge is used anywhere in this package, by design.

## Before you run E4: review the Urdu/Roman Urdu questions

The v2 dataset's Urdu and Roman Urdu questions are drafts
(`needs_review: true`). Review them for language correctness first — the
validator lists every unreviewed id. The dataset passed its own
validator: 40 questions (19 English, 10 Urdu, 11 Roman Urdu; 16 direct,
10 paraphrase, 8 exception, 6 unsupported), each mapped to a real policy
document stem.

## Troubleshooting

- **E1 shows 1 error for RagEvaluationHarnessLocalRunTest**: that test is
  excluded by name in this runner because, on the abandoned 2026-11-01
  tree, it was an ungated `@SpringBootTest` needing a dev database.
  Current main has no such test, so the exclusion is a harmless no-op
  there — but it stays, so re-adding an ungated variant can never
  silently break the gate again.
- **E4 harness skipped**: the harness test ships in this zip at
  `java-backend/src/test/java/com/voxticket/rag/`. If it is missing,
  the zip was not extracted at the repo root — re-extract it. The file
  was not compiled in the sandbox: if test compilation fails after
  extracting, delete the file and re-run (the default gate does not
  depend on it).
- **-AutoBackend fails at startup**: check `backend_<arm>.err.log` in the
  run folder. The usual cause is missing keys in the PowerShell session
  (export GROQ_API_KEY / VOYAGE_API_KEY first) or a database that is
  not running. A backend that is already running on port 8080 is used
  instead and never stopped.
- **Suite skipped (0.013s, 5 skipped)**: the suite needs
  `-Dvoxticket.phase3.eval=true`. The runner passes it. If you run the
  suite by hand, quote the WHOLE `-D` argument in PowerShell
  (`"-Dvoxticket.routing.model.path=D:\..."`) — quoting only the value
  splits at `=` and Maven fails before any test runs.
- **E4 "not measured"**: the harness needs the dev database, embeddings,
  and a populated vector store. Check `e4_harness.log` in the run folder.
- **Backend not reachable**: without `-AutoBackend`, E2 probes and E3
  live are marked BLOCKED with the reason, and the rest of the run
  continues. With `-AutoBackend`, the script starts a backend for the
  probes and per live arm instead.
- **All Spring-context tests fail with "Routing model directory is not
  configured"**: the test JVMs need env `ROUTING_MODEL_PATH` pointing
  at the folder with `model.onnx` + `tokenizer.json`. v9 defaults it
  from the resolved model path; on older versions, set it in the
  session first (`$env:ROUTING_MODEL_PATH =
  "D:\dev\VoxTicket-models\multilingual-e5-small"`) and re-run.
- **A step fails**: the runner continues with the other areas and the
  summary at the end shows each area's status. Logs live in the run folder.

## What to quote in the thesis

Quote only numbers that appear in a generated report with a result JSON
behind them, and always with their sample size (n), their arm/status
labels (DECLARED arms, ESTIMATED costs), and the run's commit hash and
date from the report header. Keep "not measured" cells honest — a stated
limitation is a strength in a thesis, an invented number is not.
