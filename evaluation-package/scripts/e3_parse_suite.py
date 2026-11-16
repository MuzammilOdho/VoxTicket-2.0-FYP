"""E3 routing-suite parser: extract the Phase3EvaluationSuite numbers.

Parses the suite's console output (the 'Phase3 ...' lines) and, when present,
its run-info.json, into e3_routing.json. The suite's own verdicts are the
evidence; this script only transcribes them, it never recomputes or adjusts
a number.

Usage:
  python scripts/e3_parse_suite.py --log results/phase3_console.txt \
      [--run-info java-backend/evaluation/routing/results/run-info.json] \
      --out results/e3_routing.json
"""

import argparse
import json
import re
import sys
from pathlib import Path

METRIC_RE = re.compile(
    r"Phase3\s+(?P<section>baseline VALIDATION|calibration|FINAL_TEST \S+):"
    r"(?:\s+selected simpleMargin=(?P<margin>[\d.]+))?"
    r"(?:\s+before=acc=(?P<before_acc>[\d.]+))?"
    r"(?:\s+balAcc=(?P<before_bal>[\d.]+))?"
    r"(?:\s+macroF1=(?P<before_f1>[\d.]+))?"
    r"(?:\s+under=(?P<before_under>\d+))?"
    r"(?:\s+over=(?P<before_over>\d+))?"
    r"(?:\s+t2rate=(?P<before_t2>[\d.]+))?"
    r"(?:\s+after=acc=(?P<after_acc>[\d.]+))?"
    r"(?:\s+balAcc=(?P<after_bal>[\d.]+))?"
    r"(?:\s+macroF1=(?P<after_f1>[\d.]+))?"
    r"(?:\s+under=(?P<after_under>\d+))?"
    r"(?:\s+over=(?P<after_over>\d+))?"
    r"(?:\s+t2rate=(?P<after_t2>[\d.]+))?"
    r"(?:\s+acc=(?P<acc>[\d.]+))?"
    r"(?:\s+balAcc=(?P<bal>[\d.]+))?"
    r"(?:\s+macroF1=(?P<f1>[\d.]+))?"
    r"(?:\s+under=(?P<under>\d+))?"
    r"(?:\s+over=(?P<over>\d+))?"
    r"(?:\s+t2rate=(?P<t2>[\d.]+))?"
)
INFERENCE_RE = re.compile(
    r"Phase3 inference: validation=(?P<val>\d+) finalTest=(?P<tst>\d+)"
    r" semanticTurns=(?P<sem>\d+) embeddingCalls=(?P<emb>\d+)"
)
LATENCY_RE = re.compile(
    r"^(?P<path>structural|semantic),"
    r"(?P<mean>[\d.]+),(?P<p50>[\d.]+),(?P<p95>[\d.]+),(?P<max>[\d.]+)\s*$"
)
RUNINFO_RE = re.compile(r"Phase3 run-info: RunInfo\[(?P<body>.*)\]")
TESTS_RE = re.compile(r"Tests run: (?P<run>\d+), Failures: (?P<fail>\d+), "
                       r"Errors: (?P<err>\d+), Skipped: (?P<skip>\d+)")


def parse_runinfo_body(body):
    info = {}
    for part in body.split(", "):
        if "=" in part:
            key, value = part.split("=", 1)
            info[key.strip()] = value.strip()
    return info


def main():
    parser = argparse.ArgumentParser(description="Parse Phase3 suite output into e3_routing.json")
    parser.add_argument("--log", required=True)
    parser.add_argument("--run-info", default="")
    parser.add_argument("--out", required=True)
    args = parser.parse_args()

    log_path = Path(args.log)
    if not log_path.is_file():
        print(f"E3 routing: suite log not found: {log_path} (run the suite first)")
        result = {"area": "E3-routing", "status": "not_run",
                  "reason": f"suite console log not found: {log_path}"}
    else:
        text = log_path.read_text(encoding="utf-8", errors="replace")
        sections, latency, inference, runinfo, tests = {}, {}, None, {}, None
        in_latency = False
        for line in text.splitlines():
            match = METRIC_RE.search(line)
            if match:
                data = {k: v for k, v in match.groupdict().items() if v is not None}
                sections[match.group("section")] = data
            match = INFERENCE_RE.search(line)
            if match:
                inference = {k: int(v) for k, v in match.groupdict().items()}
            if line.startswith("Phase3 latency"):
                in_latency = True
                continue
            if in_latency:
                match = LATENCY_RE.match(line.strip())
                if match:
                    latency[match.group("path")] = {
                        k: float(v) for k, v in match.groupdict().items() if k != "path"
                    }
                elif line.strip() and not line.startswith("path,"):
                    in_latency = False
            match = RUNINFO_RE.search(line)
            if match:
                runinfo = parse_runinfo_body(match.group("body"))
            match = TESTS_RE.search(line)
            if match and "Phase3EvaluationSuite" in text[max(0, text.find(line) - 200):text.find(line) + len(line)]:
                tests = {k: int(v) for k, v in match.groupdict().items()}
        if not sections:
            result = {"area": "E3-routing", "status": "not_found",
                      "reason": "No 'Phase3 ...' lines in the log. The suite may have "
                      "been skipped (check the voxticket.phase3.eval flag) or "
                      "the wrong log file was given."}
        else:
            result = {
                "area": "E3-routing",
                "status": "measured",
                "method": "Transcribed from Phase3EvaluationSuite console output. "
                "Calibration is tuned on the validation split only; the "
                "final-test split is held out and never used for tuning.",
                "sections": sections,
                "inference": inference,
                "latency_ms": latency,
                "run_info": runinfo,
                "suite_tests": tests,
            }
    if args.run_info:
        run_info_path = Path(args.run_info)
        if run_info_path.is_file():
            try:
                result["run_info_file"] = json.loads(run_info_path.read_text(encoding="utf-8"))
            except json.JSONDecodeError as exc:
                result["run_info_file_error"] = str(exc)
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(result, indent=2, ensure_ascii=False), encoding="utf-8")
    print(f"E3 routing: status={result['status']} -> {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
