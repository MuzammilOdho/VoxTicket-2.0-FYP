"""E3 live runner: run one arm of the three-arm comparison.

Sends every scenario in datasets/e3_scenarios.json to POST /api/v1/chat on a
running backend and records latency plus the full response text. The arm is
DECLARED by the caller (--arm): the chat response carries no tier field, so
the arm cannot be verified from the response alone. Start the backend with
that strategy (VOXTICKET_AI_SELECTOR_STRATEGY, default HYBRID) and confirm
it from backend log lines 'event=routing_decision ... strategy=...' for the
run window, or run e3_log_parse.py over a saved backend log.

Rate limiting: every call is paced by the shared limiter (default 10 rpm;
Groq already 429'd at single-session load). On 429 the runner backs off
(30/60/120/240s, honouring Retry-After) and retries; every 429 is recorded
in the output as data. Results are written incrementally after each
scenario, so an interrupted run resumes instead of restarting: re-running
with the same --out skips already-recorded scenario ids.

Token and cost figures are ESTIMATES (words x 1.33), not metered tokens,
and are only computed when --price-per-1k is given. They are labelled as
estimates in every output. No procedure is ever confirmed inside a run, so
demo orders stay untouched. Human quality scores are NOT produced here:
run e3_aggregate.py afterwards with a filled human-scores CSV.

Usage:
  python scripts/e3_live.py --base-url http://localhost:8080 \
      --scenarios datasets/e3_scenarios.json --arm HYBRID \
      --out results/e3_live_HYBRID.json [--customer-phone +923001234567] \
      [--rpm 10] [--price-per-1k 0.0003]
"""

import argparse
import csv
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from ratelimit import RateLimiter, paced_request  # noqa: E402


def find_phone(obj):
    """First string that looks like a phone number, anywhere in a JSON value."""
    if isinstance(obj, dict):
        for value in obj.values():
            found = find_phone(value)
            if found:
                return found
    elif isinstance(obj, list):
        for value in obj:
            found = find_phone(value)
            if found:
                return found
    elif isinstance(obj, str):
        digits = obj.replace("+", "").replace(" ", "")
        if digits.isdigit() and len(digits) >= 10:
            return obj
    return None


def main():
    parser = argparse.ArgumentParser(description="Run one E3 live arm against the backend")
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--scenarios", required=True)
    parser.add_argument("--arm", required=True, choices=["ALWAYS_TIER_1", "ALWAYS_TIER_2", "HYBRID"])
    parser.add_argument("--customer-phone", default="")
    parser.add_argument("--rpm", type=float, default=10)
    parser.add_argument("--price-per-1k", type=float, default=None)
    parser.add_argument("--out", required=True)
    args = parser.parse_args()

    limiter = RateLimiter(rpm=args.rpm)
    base = args.base_url.rstrip("/")
    scenarios = json.loads(Path(args.scenarios).read_text(encoding="utf-8"))["scenarios"]

    # Customer phone: the flag wins; otherwise resolve one from the demo
    # endpoint (the same way the E2 prober does); otherwise run without
    # one and say so. The seeded demo phones are generated, not real PII,
    # but only a masked prefix is stored in the output.
    phone = args.customer_phone or None
    phone_source = "flag" if phone else "none"
    if not phone:
        status, body, _ = paced_request(limiter, "GET", base + "/api/v1/demo/customer/random")
        if status == 200:
            try:
                phone = find_phone(json.loads(body))
            except Exception:
                phone = None
            if phone:
                phone_source = "demo"
    print(f"E3 live [{args.arm}]: customer phone source={phone_source}")

    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    existing = {}
    if out.is_file():
        try:
            existing = json.loads(out.read_text(encoding="utf-8"))
        except json.JSONDecodeError:
            existing = {}
    done = {row["scenario_id"] for row in existing.get("turns", [])}
    turns = list(existing.get("turns", []))
    # Keep rate-limit accounting cumulative across resumed invocations: a
    # resumed run must not silently erase the earlier run's 429s.
    prior = existing.get("rate_limiting", {})
    limiter.calls += prior.get("calls_made", 0)
    limiter.rate_limit_hits += prior.get("rate_limit_hits_429", 0)
    limiter.backoff_seconds_total += prior.get("backoff_seconds_total", 0.0)
    limiter.rate_limit_events += prior.get("rate_limit_events", [])
    print(f"E3 live [{args.arm}]: resuming with {len(done)} already-recorded turns.")

    for scenario in scenarios:
        if scenario["id"] in done:
            continue
        data = {"message": scenario["message"]}
        if phone:
            data["customerPhone"] = phone
        status, body, latency_ms = paced_request(
            limiter, "POST", base + "/api/v1/chat", data=data
        )
        text, requires_v, requires_c = "", None, None
        if status == 200:
            try:
                payload = json.loads(body)
                text = payload.get("text", "")
                requires_v = payload.get("requiresVerification")
                requires_c = payload.get("requiresConfirmation")
            except json.JSONDecodeError:
                text = body[:1000]
        words = len(text.split())
        est_tokens = round(words * 1.33)
        est_cost = (
            round(est_tokens / 1000.0 * args.price_per_1k, 6)
            if args.price_per_1k is not None else None
        )
        turns.append({
            "scenario_id": scenario["id"], "kind": scenario["kind"],
            "language": scenario["language"], "message": scenario["message"],
            "declared_arm": args.arm, "status": status,
            "latency_ms": round(latency_ms, 1) if status else None,
            "response_text": text, "requires_verification": requires_v,
            "requires_confirmation": requires_c, "response_words": words,
            "estimated_tokens": est_tokens, "estimated_cost_usd": est_cost,
            "error": None if status == 200 else body[:300],
        })
        result = {
            "area": "E3-live", "declared_arm": args.arm,
            "customer_phone_source": phone_source,
            "customer_phone_masked": (phone[:6] + "...") if phone else None,
            "arm_verification": "DECLARED ONLY: the chat response carries no tier "
            "field. Confirm the arm from backend log lines "
            "'event=routing_decision ... strategy=...' for this run window.",
            "cost_note": "Token and cost figures are ESTIMATES (words x 1.33), not "
            "metered tokens, computed only when --price-per-1k is given.",
            "rate_limiting": limiter.stats(),
            "turns": turns,
        }
        out.write_text(json.dumps(result, indent=2, ensure_ascii=False), encoding="utf-8")
        print(f"E3 live [{args.arm}] {scenario['id']}: status={status} "
              f"latency_ms={turns[-1]['latency_ms']}")

    # Human-scores template: one row per turn, scores blank for the user.
    template = out.with_name(out.stem + "_human_scores_template.csv")
    if not template.is_file():
        with template.open("w", newline="", encoding="utf-8") as handle:
            writer = csv.writer(handle)
            writer.writerow(["scenario_id", "declared_arm", "response_text",
                             "task_completed", "quality_1_to_5", "notes"])
            for turn in turns:
                writer.writerow([turn["scenario_id"], args.arm, turn["response_text"],
                                 "", "", ""])
        print(f"E3 live [{args.arm}]: human-scores template -> {template}")
    print(f"E3 live [{args.arm}]: {len(turns)} turns, "
          f"429s={limiter.rate_limit_hits} -> {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
