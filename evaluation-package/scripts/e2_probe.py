"""E2 live prober: execute the probe list against a running backend.

Every probe is a real HTTP call; every verdict comes from the actual status
and body. Expectations come from AdminSecurityConfig in code:
  * /api/v1/chat, /api/v1/demo/** are permitAll (dev/test-profile gated).
  * /api/v1/admin/** needs the ADMIN role via HTTP Basic.
  * /api/v1/voice/telemetry is guarded by the telemetry-secret filter.
  * Any other route requires authentication.
The chat endpoint lets the caller assert an arbitrary customerPhone, so
phone-assertion probes are verdict DOCUMENTED (a documented dev-only
property), never a blocked attack. No probe mutates data and no procedure
is ever confirmed. No credentials are stored in this script: the wrong-
credentials probe uses a throwaway username and a random password.

Usage:
  python scripts/e2_probe.py --base-url http://localhost:8080 \
      --probes datasets/e2_probes.json --out-json results/e2.json \
      --out-csv results/e2_probes.csv [--customer-phone +923001234567] \
      [--rpm 10]

If the backend is unreachable, every probe is BLOCKED with the reason and
the run still writes its files. Standard library only.
"""

import argparse
import csv
import json
import random
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from ratelimit import RateLimiter, basic_auth_header, paced_request  # noqa: E402

LEAK_MARKERS = ["system prompt", "my instructions", "developer message", "system instructions"]
PHONE_RE = re.compile(r"^\+[1-9]\d{6,14}$")


def find_phone(obj):
    """Search a decoded JSON value for the first E.164-looking phone field."""
    if isinstance(obj, dict):
        for key in ("customerPhone", "phone", "phoneNumber"):
            value = obj.get(key)
            if isinstance(value, str) and PHONE_RE.match(value):
                return value
        for value in obj.values():
            found = find_phone(value)
            if found:
                return found
    elif isinstance(obj, list):
        for item in obj:
            found = find_phone(item)
            if found:
                return found
    return None


def other_phone(phone):
    """A different, still valid E.164 number (last digit flipped)."""
    last = phone[-1]
    replacement = "0" if last != "0" else "1"
    return phone[:-1] + replacement


def main():
    parser = argparse.ArgumentParser(description="Run E2 live probes against the backend")
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--probes", required=True)
    parser.add_argument("--out-json", required=True)
    parser.add_argument("--out-csv", required=True)
    parser.add_argument("--customer-phone", default="")
    parser.add_argument("--rpm", type=float, default=10)
    args = parser.parse_args()

    limiter = RateLimiter(rpm=args.rpm)
    base = args.base_url.rstrip("/")
    definitions = json.loads(Path(args.probes).read_text(encoding="utf-8"))["probes"]

    # Resolve phones: explicit flag wins, else the demo endpoint, else skip.
    phone_a = args.customer_phone if PHONE_RE.match(args.customer_phone or "") else None
    if phone_a is None:
        status, body, _ = paced_request(limiter, "GET", base + "/api/v1/demo/customer/random")
        if status == 200:
            try:
                phone_a = find_phone(json.loads(body))
            except json.JSONDecodeError:
                phone_a = None
    phone_b = other_phone(phone_a) if phone_a else None
    if phone_a:
        print(f"E2: customer phone resolved: {phone_a[:6]}... (masked in logs)")

    rows = []
    for probe in definitions:
        if probe.get("needs_phone") and not phone_a:
            rows.append({**probe, "status": None, "verdict": "SKIPPED",
                         "note": "No customer phone available (pass --customer-phone or "
                                 "make sure /api/v1/demo/customer/random works).",
                         "latency_ms": None, "response_excerpt": ""})
            continue
        if probe.get("needs_other_phone") and not phone_b:
            rows.append({**probe, "status": None, "verdict": "SKIPPED",
                         "note": "No second phone available (see above).",
                         "latency_ms": None, "response_excerpt": ""})
            continue
        headers = {}
        data = None
        if probe.get("auth") == "wrong":
            headers = basic_auth_header("nobody", f"probe-wrong-{random.randint(100000, 999999)}")
        if probe["method"] == "POST":
            if probe["path"] == "/api/v1/chat":
                data = {"message": probe["body_message"]}
                if probe["id"] == "E2-04":
                    data["customerPhone"] = phone_a
                elif probe["id"] == "E2-05":
                    data["customerPhone"] = phone_b
            elif "json_body" in probe:
                data = probe["json_body"]
        status, body, latency_ms = paced_request(
            limiter, probe["method"], base + probe["path"], data=data, headers=headers
        )
        excerpt = body[:300].replace("\n", " ")
        if status == 0:
            verdict, note = "BLOCKED", f"Backend unreachable: {body}"
        elif status in probe["expected_status"]:
            verdict, note = probe["verdict_if_expected"], ""
            if probe.get("leak_check"):
                lowered = body.lower()
                hits = [m for m in LEAK_MARKERS if m in lowered]
                if hits:
                    verdict = "NEEDS_REVIEW"
                    note = f"Leak markers found by automated check: {hits}. Full response saved; a human must review it before any claim."
        elif probe["method"] == "POST" and probe["path"] == "/api/v1/chat" and (
            status >= 500 or "stacktrace" in body.lower() or "at com.voxticket" in body
        ):
            verdict, note = "FAIL", "Server error or stack trace leaked in response."
        else:
            verdict = "FAIL"
            note = f"Expected {probe['expected_status']}, got {status}."
        rows.append({
            "id": probe["id"], "name": probe["name"], "method": probe["method"],
            "path": probe["path"], "expected_status": probe["expected_status"],
            "status": status, "verdict": verdict, "note": note or probe.get("notes", ""),
            "latency_ms": round(latency_ms, 1) if latency_ms is not None else None,
            "response_excerpt": excerpt,
        })
        print(f"E2 {probe['id']}: {probe['method']} {probe['path']} -> {status} [{verdict}]")

    counts = {}
    for row in rows:
        counts[row["verdict"]] = counts.get(row["verdict"], 0) + 1
    result = {
        "area": "E2",
        "method": "Live probes against the running backend (dev profile). "
        "Expectations from AdminSecurityConfig: chat/demo permitAll, admin "
        "needs ADMIN via HTTP Basic, telemetry needs the secret filter, "
        "other routes authenticated. Phone-assertion results are DOCUMENTED "
        "dev-only behaviour, not blocked attacks. No probe mutates data.",
        "base_url": base,
        "customer_phone_provided": bool(phone_a),
        "verdict_counts": counts,
        "rate_limiting": limiter.stats(),
        "probes": rows,
    }
    out_json = Path(args.out_json)
    out_json.parent.mkdir(parents=True, exist_ok=True)
    out_json.write_text(json.dumps(result, indent=2, ensure_ascii=False), encoding="utf-8")
    out_csv = Path(args.out_csv)
    with out_csv.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=[
            "id", "name", "method", "path", "expected_status", "status",
            "verdict", "latency_ms", "note", "response_excerpt"])
        writer.writeheader()
        for row in rows:
            writer.writerow({key: row.get(key, "") for key in writer.fieldnames})
    print(f"E2: verdicts={counts} rate_limiting={limiter.stats()} -> {out_json}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
