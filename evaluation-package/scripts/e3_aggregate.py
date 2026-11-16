"""E3 aggregator: compare the live arms on identical scenarios.

Reads one e3_live_<ARM>.json per arm plus, when present, a filled
human-scores CSV (scenario_id, task_completed, quality_1_to_5). Latency is
measured; quality and completion come ONLY from the human scores file —
without it, quality cells stay 'not measured'. Never an LLM judge.

Usage:
  python scripts/e3_aggregate.py --arm-files results/e3_live_ALWAYS_TIER_1.json \
      results/e3_live_ALWAYS_TIER_2.json results/e3_live_HYBRID.json \
      [--human-scores results/e3_live_HYBRID_human_scores.csv] \
      --out results/e3_comparison.json
"""

import argparse
import csv
import json
import statistics
import sys
from pathlib import Path


def percentile(values, pct):
    if not values:
        return None
    ordered = sorted(values)
    rank = (len(ordered) - 1) * pct / 100.0
    lower, upper = int(rank), min(int(rank) + 1, len(ordered) - 1)
    frac = rank - lower
    return round(ordered[lower] * (1 - frac) + ordered[upper] * frac, 1)


def arm_stats(turns):
    latencies = [t["latency_ms"] for t in turns if t["latency_ms"] is not None]
    costs = [t["estimated_cost_usd"] for t in turns if t["estimated_cost_usd"] is not None]
    tokens = [t["estimated_tokens"] for t in turns]
    return {
        "turns": len(turns),
        "latency_ms": {
            "mean": round(statistics.mean(latencies), 1) if latencies else None,
            "median": percentile(latencies, 50),
            "p95": percentile(latencies, 95),
        },
        "estimated_tokens_mean": round(statistics.mean(tokens), 1) if tokens else None,
        "estimated_cost_usd_mean": round(statistics.mean(costs), 6) if costs else None,
        "errors": sum(1 for t in turns if t["status"] != 200),
    }


def main():
    parser = argparse.ArgumentParser(description="Aggregate E3 live arms into a comparison")
    parser.add_argument("--arm-files", nargs="+", required=True)
    parser.add_argument("--human-scores", nargs="*", default=[])
    parser.add_argument("--out", required=True)
    args = parser.parse_args()

    arms = {}
    by_scenario = {}
    for path in args.arm_files:
        data = json.loads(Path(path).read_text(encoding="utf-8"))
        arm = data["declared_arm"]
        arms[arm] = {"stats": arm_stats(data["turns"]), "turns": data["turns"],
                     "rate_limiting": data.get("rate_limiting", {})}
        for turn in data["turns"]:
            by_scenario.setdefault(turn["scenario_id"], {})[arm] = turn

    human = {}
    for scores_file in args.human_scores:
        if not Path(scores_file).is_file():
            continue
        with Path(scores_file).open(newline="", encoding="utf-8") as handle:
            for row in csv.DictReader(handle):
                if (row.get("task_completed") or "").strip() or (row.get("quality_1_to_5") or "").strip():
                    human[(row["scenario_id"], row.get("declared_arm", ""))] = row
    if human:
        for arm, info in arms.items():
            judged = [human[k] for k in human if k[1] == arm]
            completed = sum(1 for row in judged if row["task_completed"].strip().lower() in ("yes", "y", "true", "1"))
            qualities = []
            for row in judged:
                try:
                    qualities.append(float(row["quality_1_to_5"]))
                except (TypeError, ValueError):
                    pass
            info["stats"]["human_judged_turns"] = len(judged)
            info["stats"]["task_completion_rate"] = (
                round(completed / len(judged), 4) if judged else None)
            info["stats"]["quality_mean_1_to_5"] = (
                round(statistics.mean(qualities), 2) if qualities else None)
    else:
        for info in arms.values():
            info["stats"]["task_completion_rate"] = None
            info["stats"]["quality_mean_1_to_5"] = None

    paired = []
    for scenario_id, per_arm in sorted(by_scenario.items()):
        row = {"scenario_id": scenario_id}
        for arm, turn in per_arm.items():
            row[arm] = {"latency_ms": turn["latency_ms"], "status": turn["status"]}
        paired.append(row)

    result = {
        "area": "E3-live",
        "method": "Same scenarios, same order, every arm. Latency is measured. "
        "Task completion and quality come ONLY from the human-scores file; "
        "without it they are 'not measured'. Costs are estimates (words x "
        "1.33) when a price was given, never metered tokens. Arms are "
        "declared by the caller and must be confirmed from backend logs.",
        "arms": {arm: info["stats"] for arm, info in arms.items()},
        "rate_limiting": {arm: info["rate_limiting"] for arm, info in arms.items()},
        "paired_scenarios": paired,
        "human_scores_used": bool(human),
    }
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(result, indent=2, ensure_ascii=False), encoding="utf-8")
    print(f"E3 comparison: arms={list(arms)} human_scores={bool(human)} -> {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
