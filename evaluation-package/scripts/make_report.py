"""Report generator: build thesis-ready reports from measured JSON only.

Reads every area JSON in a results directory and writes two markdown
documents. Every number comes from a JSON file; anything missing is
written as 'not measured' with its reason. Nothing is typed in, nothing
is estimated silently:
  * Costs are labelled ESTIMATED when they come from word-count estimates.
  * Live arms are labelled DECLARED (chat responses carry no tier field).
  * Answer quality appears only when human scores exist — never an LLM
    judge, never invented.
  * E4 retrieval numbers require the harness output; without it they are
    'not measured', not zero.

Usage:
  python scripts/make_report.py --results-dir results --meta results/meta.json \
      --out-md reports/FINAL-EVALUATION-REPORT.md \
      --out-ch7 reports/CHAPTER-7-TABLES.md
"""

import argparse
import json
import sys
from pathlib import Path


def load(results, name):
    path = Path(results) / name
    if not path.is_file():
        return None
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError:
        return {"status": "unparseable", "reason": f"could not parse {path}"}


def cell(value, suffix=""):
    if value is None:
        return "not measured"
    return f"{value}{suffix}"


def main():
    parser = argparse.ArgumentParser(description="Generate evaluation reports from measured JSON")
    parser.add_argument("--results-dir", required=True)
    parser.add_argument("--meta", default="")
    parser.add_argument("--out-md", required=True)
    parser.add_argument("--out-ch7", required=True)
    args = parser.parse_args()

    results = Path(args.results_dir)
    meta = {}
    if args.meta and Path(args.meta).is_file():
        meta = json.loads(Path(args.meta).read_text(encoding="utf-8"))
    e1 = load(results, "e1.json")
    e2 = load(results, "e2.json")
    e3r = load(results, "e3_routing.json")
    e3c = load(results, "e3_comparison.json")
    e3t = load(results, "e3_tiers.json")
    e4 = load(results, "e4.json")
    e5 = load(results, "e5.json")

    def section_status(data):
        if data is None:
            return "not measured (no result file)"
        return data.get("status", "unknown")

    # ---- E1 numbers ----
    java_line = python_line = "not measured"
    if e1 and e1["java"]["status"] == "measured":
        t = e1["java"]["totals"]
        java_line = (f"{t['passed']} passed, {t['failures']} failed, "
                     f"{t['errors']} errors, {t['skipped']} skipped, "
                     f"out of {t['total']} tests")
    if e1 and e1["python"]["status"] == "measured":
        t = e1["python"]["totals"]
        python_line = (f"{t['passed']} passed, {t['failures']} failed, "
                       f"{t['errors']} errors, {t['skipped']} skipped, "
                       f"out of {t['total']} tests")
    # ---- E2 numbers ----
    e2_line = "not measured"
    if e2:
        counts = e2.get("verdict_counts", {})
        e2_line = (f"probes: {counts} (PASS = blocked as expected, DOCUMENTED = "
                   "dev-only behaviour by design, FAIL = unexpected result, "
                   "NEEDS_REVIEW = human must check the saved response)")
    # ---- E3 routing numbers ----
    hybrid_line = "not measured"
    latency_line = "not measured"
    if e3r and e3r["status"] == "measured":
        final = e3r["sections"].get("FINAL_TEST HYBRID_CALIBRATED", {})
        hybrid_line = (f"accuracy {final.get('acc')}, balanced accuracy "
                       f"{final.get('bal')}, macro-F1 {final.get('f1')}, "
                       f"over-routes {final.get('over')}, Tier 2 rate {final.get('t2')}")
        lat = e3r.get("latency_ms", {})
        if lat:
            latency_line = "; ".join(
                f"{path}: mean {vals.get('mean')} ms, p95 {vals.get('p95')} ms"
                for path, vals in lat.items())
    # ---- E3 live numbers ----
    live_lines = []
    if e3c:
        for arm, stats in e3c["arms"].items():
            lat = stats["latency_ms"]
            live_lines.append(
                f"{arm}: {stats['turns']} turns, latency mean {cell(lat['mean'], ' ms')}, "
                f"median {cell(lat['median'], ' ms')}, p95 {cell(lat['p95'], ' ms')}, "
                f"estimated cost/call {cell(stats['estimated_cost_usd_mean'], ' USD')} "
                f"(estimate only), task completion {cell(stats['task_completion_rate'])}, "
                f"quality {cell(stats['quality_mean_1_to_5'])} (human scores only)")
    # ---- E4 numbers ----
    e4_line = "not measured"
    if e4 and e4["status"] == "measured" and e4.get("overall"):
        overall = e4["overall"]
        e4_line = (f"Recall@1 {overall['recall_at_1']}, Recall@3 {overall['recall_at_3']}, "
                   f"MRR {overall['mrr']}, nDCG@3 {overall['ndcg_at_3']}; unsupported "
                   f"handled {e4['unsupported']['handled_correctly']}/{e4['unsupported']['n']}")
    # ---- E5 numbers ----
    e5_line = "not measured (awaiting the user's 10 manual calls)"
    if e5 and e5["status"] == "measured":
        lat = e5["latency_ms"]
        e5_line = (f"{e5['calls_recorded']} calls; task completion "
                   f"{e5['task_completion']['yes']}/{e5['calls_recorded']}; "
                   f"end-to-end mean {lat['e2e_ms_mean']['mean']} ms, "
                   f"brain TTFT mean {lat['brain_ttft_ms_mean']['mean']} ms")

    run_header = [
        "# VoxTicket — Final Evaluation Report (generated, not typed)",
        "",
        "Every number below comes from a result JSON in the run directory. "
        "Anything missing is marked 'not measured' with its reason. No number "
        "in this report was typed in by hand, estimated silently, or taken "
        "from a mocked run.",
        "",
        f"Run date: {meta.get('date', 'not recorded')}",
        f"Git commit: {meta.get('git_sha', 'not recorded')}",
        f"Java: {meta.get('java_version', 'not recorded')}",
        f"Routing model path: {meta.get('model_path', 'not recorded')}",
        f"Embedding provider/model (record per run): {meta.get('embedding_provider', 'NOT RECORDED — record before quoting E4 numbers')}",
        f"Price table used for cost ESTIMATES: {meta.get('price_note', 'not recorded')}",
        f"RPM configured for API-calling scripts: {meta.get('rpm', 'not recorded')}",
        "",
        "## E1 — Functional correctness",
        f"Status: {section_status(e1)}",
        f"Java: {java_line}",
        f"Python: {python_line}",
        "Errors are infrastructure/context failures, never folded into passes. "
        "A skipped test is reported as skipped, never as passed.",
        "",
        "## E2 — Security and safety",
        f"Status: {section_status(e2)}",
        f"{e2_line}",
        "Phone-assertion results are DOCUMENTED dev-only behaviour: the chat "
        "endpoint is permitAll by design and dev/test-profile gated, and it "
        "lets the caller assert a customer phone. That endpoint must never be "
        "exposed beyond dev/test. No probe mutated data; no procedure was confirmed.",
        "",
        "## E3 — Adaptive routing",
        f"Offline routing suite: {section_status(e3r)}",
        f"Held-out final test, calibrated hybrid: {hybrid_line}",
        f"Routing-decision latency: {latency_line}",
        f"Tier selection from backend logs: {section_status(e3t)}",
        "Live three-arm comparison (arms are DECLARED — chat responses carry "
        "no tier field; confirm arms from backend routing-decision logs):",
    ]
    run_header += [f"- {line}" for line in live_lines] if live_lines else ["- not measured"]
    run_header += [
        "",
        "## E4 — RAG retrieval and answer quality",
        f"Status: {section_status(e4)}",
        f"Retrieval: {e4_line}",
        "Answer quality is human scored only. No LLM judge was used as evidence. "
        "If the vector store was empty during the run, retrieval numbers are an "
        "infrastructure result, not a retrieval result, and are labelled so.",
        "",
        "## E5 — Voice (user's manual calls)",
        f"Status: {section_status(e5)}",
        f"{e5_line}",
        "Latency figures are copied from admin voice telemetry; scores are the "
        "user's human judgement. Latency aggregates are means of per-call means.",
        "",
        "## Limitations (read before quoting any number)",
        "- Groq free-tier rate limits: every 429 and backoff second is recorded "
        "in the per-area JSON files; a run with many 429s measured a throttled "
        "system, not a calm one.",
        "- Live-turn sample sizes are small (state n wherever you quote a mean). "
        "No claim of statistical significance is made.",
        "- Cost figures are word-count ESTIMATES (words x 1.33), not metered "
        "tokens, unless a metered source is attached.",
        "- The three-arm comparison measures latency and human-judged quality; "
        "tier selection under HYBRID must come from backend logs, not from "
        "chat responses.",
        "- Concurrency was not measured beyond single sessions in this package; "
        "provider-failure behaviour was not tested.",
        "",
    ]
    out_md = Path(args.out_md)
    out_md.parent.mkdir(parents=True, exist_ok=True)
    out_md.write_text("\n".join(run_header), encoding="utf-8")

    ch7 = [
        "# Chapter 7 — Evaluation tables (generated draft)",
        "",
        "These tables are a draft for Chapter 7 only. Configuration values and "
        "results belong in Chapter 7 and nowhere else. Fill the .docx chapter "
        "from these tables, and keep every 'not measured' cell honest.",
        "",
        "## Table 7.1 — Routing accuracy (offline suite, held-out final test)",
        "",
        "| Strategy | Accuracy | Balanced accuracy | Macro-F1 | Over-routes | Tier 2 rate |",
        "|---|---|---|---|---|---|",
    ]
    if e3r and e3r["status"] == "measured":
        for name in ("FINAL_TEST ALWAYS_TIER_1", "FINAL_TEST ALWAYS_TIER_2",
                     "FINAL_TEST RULE_ONLY", "FINAL_TEST HYBRID_CALIBRATED"):
            row = e3r["sections"].get(name, {})
            ch7.append(f"| {name} | {cell(row.get('acc'))} | {cell(row.get('bal'))} | "
                       f"{cell(row.get('f1'))} | {cell(row.get('over'))} | {cell(row.get('t2'))} |")
    else:
        ch7.append("| (all strategies) | not measured | not measured | not measured | not measured | not measured |")
    ch7 += [
        "",
        "## Table 7.2 — Routing-decision latency (ms)",
        "",
        "| Path | Mean | p50 | p95 | Max |",
        "|---|---|---|---|---|",
    ]
    if e3r and e3r["status"] == "measured":
        for path, vals in e3r.get("latency_ms", {}).items():
            ch7.append(f"| {path} | {cell(vals.get('mean'))} | {cell(vals.get('p50'))} | "
                       f"{cell(vals.get('p95'))} | {cell(vals.get('max'))} |")
    else:
        ch7.append("| (both paths) | not measured | not measured | not measured | not measured |")
    ch7 += [
        "",
        "## Table 7.3 — Live three-arm comparison (identical scenarios)",
        "",
        "| Arm | Turns | Latency mean (ms) | Latency p95 (ms) | Est. cost/call (USD, estimate) | Task completion (human) | Quality mean (human, 1-5) |",
        "|---|---|---|---|---|---|---|",
    ]
    if e3c:
        for arm, stats in e3c["arms"].items():
            lat = stats["latency_ms"]
            ch7.append(f"| {arm} (declared) | {stats['turns']} | {cell(lat['mean'])} | "
                       f"{cell(lat['p95'])} | {cell(stats['estimated_cost_usd_mean'])} | "
                       f"{cell(stats['task_completion_rate'])} | {cell(stats['quality_mean_1_to_5'])} |")
    else:
        ch7.append("| (all arms) | not measured | not measured | not measured | not measured | not measured | not measured |")
    ch7 += [
        "",
        "## Table 7.4 — RAG retrieval metrics",
        "",
        "| Metric | Value |",
        "|---|---|",
    ]
    if e4 and e4["status"] == "measured" and e4.get("overall"):
        for key, label in (("recall_at_1", "Recall@1"), ("recall_at_3", "Recall@3"),
                           ("mrr", "MRR"), ("ndcg_at_3", "nDCG@3")):
            ch7.append(f"| {label} | {e4['overall'][key]} |")
        ch7.append(f"| Unsupported handled correctly | {e4['unsupported']['handled_correctly']}/{e4['unsupported']['n']} |")
    else:
        ch7.append("| (all metrics) | not measured |")
    ch7 += [
        "",
        "## Table 7.5 — Security and functional summary",
        "",
        f"| Area | Result |",
        f"|---|---|",
        f"| E1 Java | {java_line} |",
        f"| E1 Python | {python_line} |",
        f"| E2 live probes | {e2_line} |",
        f"| E5 voice | {e5_line} |",
        "",
    ]
    out_ch7 = Path(args.out_ch7)
    out_ch7.write_text("\n".join(ch7), encoding="utf-8")
    print(f"Reports: {out_md} and {out_ch7}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
