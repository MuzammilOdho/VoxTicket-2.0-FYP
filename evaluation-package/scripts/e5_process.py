"""E5 processor: turn the filled 10-call voice template into E5 metrics.

Reads templates/voice_calls_template.csv once the user has filled it after
their 10 manual calls (latency figures copied from the admin voice
telemetry) and writes e5.json plus a markdown table. Latency columns are
per-call means from telemetry, so the aggregates here are means of
per-call means — that limitation is stated in the output, not hidden.
Human scores are 1-5 per dimension; task completion is yes/no/partial.
Blank metric cells are excluded from that average and counted as missing,
never treated as zero.

Usage:
  python scripts/e5_process.py --csv results/voice_calls_filled.csv \
      --out-json results/e5.json [--out-md results/e5_table.md]
"""

import argparse
import csv
import json
import statistics
import sys
from collections import defaultdict
from pathlib import Path

LATENCY_FIELDS = ["stt_ms_mean", "brain_ttft_ms_mean", "tts_first_audio_ms_mean", "e2e_ms_mean"]
SCORE_FIELDS = ["meaning_preservation_1_5", "intent_understanding_1_5",
                "response_correctness_1_5", "naturalness_1_5", "pronunciation_1_5"]


def percentile(values, pct):
    if not values:
        return None
    ordered = sorted(values)
    rank = (len(ordered) - 1) * pct / 100.0
    lower, upper = int(rank), min(int(rank) + 1, len(ordered) - 1)
    frac = rank - lower
    return round(ordered[lower] * (1 - frac) + ordered[upper] * frac, 1)


def mean_or_none(values):
    return round(statistics.mean(values), 1) if values else None


def main():
    parser = argparse.ArgumentParser(description="Process the filled E5 voice template")
    parser.add_argument("--csv", required=True)
    parser.add_argument("--out-json", required=True)
    parser.add_argument("--out-md", default="")
    args = parser.parse_args()

    csv_path = Path(args.csv)
    if not csv_path.is_file():
        result = {"area": "E5", "status": "awaiting_user",
                  "reason": f"Filled voice template not found: {csv_path}. Do the "
                  "10 manual calls, fill templates/voice_calls_template.csv, "
                  "and pass its path with --csv."}
    else:
        with csv_path.open(newline="", encoding="utf-8") as handle:
            rows = [row for row in csv.DictReader(handle)
                    if (row.get("call_id") or "").strip()]
        filled = [row for row in rows
                  if (row.get("e2e_ms_mean") or "").strip()
                  or (row.get("task_completed") or "").strip()]
        if not filled:
            result = {"area": "E5", "status": "awaiting_user",
                      "reason": "Template found but no call has metrics or a "
                      "completion value yet. Fill it after the manual calls."}
        else:
            latencies, scores, by_language = {}, {}, defaultdict(list)
            for field in LATENCY_FIELDS:
                values = []
                for row in filled:
                    try:
                        values.append(float(row[field]))
                    except (TypeError, ValueError):
                        pass
                latencies[field] = {
                    "n": len(values), "mean": mean_or_none(values),
                    "median": percentile(values, 50), "p95": percentile(values, 95),
                }
            for field in SCORE_FIELDS:
                values = []
                for row in filled:
                    try:
                        values.append(float(row[field]))
                    except (TypeError, ValueError):
                        pass
                scores[field] = {"n": len(values), "mean": mean_or_none(values)}
            for row in filled:
                by_language[row.get("language", "") or "unknown"].append(row)
            completed = sum(1 for r in filled if (r.get("task_completed") or "").strip().lower() == "yes")
            partial = sum(1 for r in filled if (r.get("task_completed") or "").strip().lower() == "partial")
            result = {
                "area": "E5",
                "status": "measured",
                "method": "Manual live voice calls by the user; latency figures "
                "copied from admin voice telemetry; scores are the user's "
                "human judgement (1-5). Latency aggregates are means of "
                "per-call means, because telemetry is recorded per call.",
                "calls_recorded": len(filled),
                "task_completion": {
                    "yes": completed, "partial": partial,
                    "no": len(filled) - completed - partial,
                    "yes_rate": round(completed / len(filled), 4) if filled else None,
                },
                "latency_ms": latencies,
                "human_scores_1_to_5": scores,
                "by_language": {lang: len(group) for lang, group in sorted(by_language.items())},
                "calls": filled,
            }
    out_json = Path(args.out_json)
    out_json.parent.mkdir(parents=True, exist_ok=True)
    out_json.write_text(json.dumps(result, indent=2, ensure_ascii=False), encoding="utf-8")
    if args.out_md and result["status"] == "measured":
        lines = ["# E5 — Voice call metrics (user's manual calls)", "",
                 f"Calls recorded: {result['calls_recorded']}", "",
                 "| Stage | Mean (ms) | Median (ms) | p95 (ms) |", "|---|---|---|---|"]
        for field, stats in result["latency_ms"].items():
            lines.append(f"| {field} | {stats['mean']} | {stats['median']} | {stats['p95']} |")
        lines += ["", f"Task completion (yes): {result['task_completion']['yes']}"
                       f"/{result['calls_recorded']}"]
        Path(args.out_md).write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"E5: status={result['status']} -> {out_json}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
