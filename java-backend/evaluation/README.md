# VoxTicket evaluation data — routing corpus + re-anchor (2026-11-01)

## What is in this delivery

- `evaluation/routing/corpus/validation.json` — 120 routing examples (VALIDATION split).
- `evaluation/routing/corpus/final-test.json` — 120 routing examples (FINAL_TEST split).
- `src/main/java/com/voxticket/persistence/seed/SeedDatasetFactory.java` — complete file, one
  change: `ANCHOR` moved from `2026-09-01T00:00:00Z` to `2026-10-22T00:00:00Z`.
- `src/test/java/com/voxticket/persistence/seed/SeedDatasetDeterminismTest.java` — complete file,
  comment only (the anchor date mentioned in a comment, updated to match).

## Corpus rules (verified against EvalCorpus.java before delivery)

- Each file is a bare JSON array of EvalExample objects: `id, split, language,
  expectedTier, text, tags, sourceNote`. No other keys.
- Per file: 120 items = 4 languages x 30 (15 TIER_1 + 15 TIER_2).
  Languages: ENGLISH, URDU, ROMAN_URDU, CODE_SWITCH.
- IDs: `VAL|TST - EN|UR|RU|CS - T1|T2 - NNN`, matching split/language/tier.
- Tags only from the 22-tag controlled vocabulary in EvalCorpus.
- All 240 texts are unique (normalized), none equals any of the 96 prototype
  texts in `routing/examples-v1.json`, and no text appears in both files
  (the suite checks cross-split leakage). Token-Jaccard vs prototypes is
  below 0.5 for every item.
- Order numbers use the ORD-20xxx range, which appears nowhere in the
  prototypes (ORD-100xx/101xx) or the seeded demo data (ORD-10001..).
- Source note on every item: "Authored 2026-11-01 for the VoxTicket
  routing evaluation corpus."

## How to run the routing suite (from the java-backend directory)

The suite reads `evaluation/routing/corpus/` and writes
`evaluation/routing/results/` plus `inference-cache-*.json` files, all
relative to the working directory — so run Maven from `java-backend`,
not from the repo root. It needs the real ONNX model directory
(`model.onnx` + `tokenizer.json`), the same directory as ROUTING_MODEL_PATH.

```powershell
cd java-backend
mvn test -Dtest=Phase3EvaluationSuite -Dvoxticket.routing.model.path="D:\path\to\routing-model"
```

Then run the full gate to confirm nothing else moved:

```powershell
mvn clean test
```

Keep the generated `evaluation/routing/results/` and the cache files as
the experiment record for this run; record the commit, the corpus files
above, and the model directory in Chapter 7. If the suite reports a
calibration or held-out failure, send the failure output — do not edit
the corpus to make it pass without saying so, because that would
contaminate the held-out split.

## Re-anchor (voice option 1) — read before reseeding

- New anchor: `2026-10-22` = run date (2026-11-01) minus 10 days.
- Effect, because all seed dates are anchor-relative and the return window
  is 30 days: ORD-10006 was delivered anchor-minus-10 days = 2026-10-12,
  so on a 2026-11-01 run it is 20 days old and ELIGIBLE for return.
  ORD-10007 was delivered anchor-minus-60 days, so it stays EXPIRED.
  ORD-10008 stays non-returnable (final sale). All other seeded roles
  are unchanged.
- Valid run window: while ORD-10006 is still within 30 days of delivery,
  i.e. runs up to about 2026-12-01. If you run later than that, move
  ANCHOR to (your run date minus 10 days), reseed again, and record the
  new anchor in Chapter 7.
- Steps: apply the SeedDatasetFactory file, wipe the database, reseed
  (the seeder skips when customers exist, so the wipe is required, not
  optional), then verify before any live call: ORD-10006 eligible,
  ORD-10007 expired, ORD-10003 present and cancellable. Then run the
  split live script (Maria 5 turns, Ahmed 5 turns, wipe + reseed between
  repeats).
