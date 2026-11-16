"""E1 parser: turn surefire and pytest XML reports into exact E1 numbers.

Reads Java surefire reports (java-backend/target/surefire-reports/*.xml) and
one pytest junit XML file, and writes e1.json. Counts are computed from the
XML, never typed in: passed = total - failures - errors - skipped.

Usage:
  python scripts/e1_parse.py --surefire-dir java-backend/target/surefire-reports \
      --pytest-xml results/pytest.xml --out results/e1.json

Missing inputs are reported as "not_run" with a reason; nothing is invented.
Standard library only.
"""

import argparse
import json
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


def parse_surefire(directory):
    directory = Path(directory)
    if not directory.is_dir():
        return {"status": "not_run", "reason": f"surefire directory not found: {directory}"}
    files = sorted(directory.glob("TEST-*.xml"))
    if not files:
        return {"status": "not_run", "reason": f"no TEST-*.xml files in {directory}"}
    classes = []
    totals = {"total": 0, "failures": 0, "errors": 0, "skipped": 0}
    for path in files:
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError as exc:
            classes.append({"class": path.name, "status": "unparseable", "reason": str(exc)})
            continue
        suites = [root] if root.tag == "testsuite" else list(root.iter("testsuite"))
        for suite in suites:
            tests = int(suite.get("tests", 0))
            failures = int(suite.get("failures", 0))
            errors = int(suite.get("errors", 0))
            skipped = int(suite.get("skipped", 0))
            classes.append(
                {
                    "class": suite.get("name", path.name),
                    "total": tests,
                    "passed": tests - failures - errors - skipped,
                    "failures": failures,
                    "errors": errors,
                    "skipped": skipped,
                }
            )
            totals["total"] += tests
            totals["failures"] += failures
            totals["errors"] += errors
            totals["skipped"] += skipped
    totals["passed"] = totals["total"] - totals["failures"] - totals["errors"] - totals["skipped"]
    return {"status": "measured", "totals": totals, "classes": classes}


def parse_pytest(path):
    path = Path(path)
    if not path.is_file():
        return {"status": "not_run", "reason": f"pytest junit xml not found: {path}"}
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError as exc:
        return {"status": "not_run", "reason": f"unparseable pytest xml: {exc}"}
    suites = [root] if root.tag == "testsuite" else list(root.iter("testsuite"))
    totals = {"total": 0, "failures": 0, "errors": 0, "skipped": 0}
    for suite in suites:
        totals["total"] += int(suite.get("tests", 0))
        totals["failures"] += int(suite.get("failures", 0))
        totals["errors"] += int(suite.get("errors", 0))
        totals["skipped"] += int(suite.get("skipped", 0))
    totals["passed"] = totals["total"] - totals["failures"] - totals["errors"] - totals["skipped"]
    return {"status": "measured", "totals": totals}


def main():
    parser = argparse.ArgumentParser(description="Parse E1 test reports into e1.json")
    parser.add_argument("--surefire-dir", default="java-backend/target/surefire-reports")
    parser.add_argument("--pytest-xml", default="results/pytest.xml")
    parser.add_argument("--out", required=True)
    args = parser.parse_args()

    result = {
        "area": "E1",
        "method": "Counts computed from surefire XML and pytest junit XML. "
        "passed = total - failures - errors - skipped. "
        "A test error (context/infrastructure) is reported as an error, "
        "never folded into passes or failures.",
        "java": parse_surefire(args.surefire_dir),
        "python": parse_pytest(args.pytest_xml),
    }
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(result, indent=2, ensure_ascii=False), encoding="utf-8")
    print(f"E1: java={result['java']['status']} python={result['python']['status']} -> {out}")
    if result["java"]["status"] == "measured":
        print(f"  java totals: {result['java']['totals']}")
    if result["python"]["status"] == "measured":
        print(f"  python totals: {result['python']['totals']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
