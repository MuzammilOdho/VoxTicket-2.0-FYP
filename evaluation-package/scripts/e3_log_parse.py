"""E3 log parser: tier selection from backend routing-decision log lines.

The chat response carries no tier field, so the only way to verify which
tier the HYBRID arm actually used is the backend log. This script parses
lines of the form:
  event=routing_decision sessionId=<id> strategy=<S> tier=<T> reason=<R>
  margin=<M> signals=[...]
and aggregates tier counts per strategy (and per session when session ids
are given). Margin 'n/a' (forced strategies) is skipped for margin stats.

Usage:
  python scripts/e3_log_parse.py --log backend.log --out results/e3_tiers.json
      [--session-ids chat-abc,chat-def]
"""

import argparse
import json
import re
import sys
from pathlib import Path

LINE_RE = re.compile(
    r"event=routing_decision sessionId=(?P<session>\S+)"
    r" strategy=(?P<strategy>\S+) tier=(?P<tier>\S+)"
    r" reason=(?P<reason>\S+) margin=(?P<margin>\S+)"
    r" signals=\[(?P<signals>.*?)\]"
)


def main():
    parser = argparse.ArgumentParser(description="Parse routing decisions from a backend log")
    parser.add_argument("--log", required=True)
    parser.add_argument("--session-ids", default="")
    parser.add_argument("--out", required=True)
    args = parser.parse_args()

    wanted = {s.strip() for s in args.session_ids.split(",") if s.strip()} or None
    log_path = Path(args.log)
    if not log_path.is_file():
        print(f"E3 log parse: log file not found: {log_path}")
        result = {"area": "E3-tiers", "status": "not_run",
                  "reason": f"log file not found: {log_path}"}
    else:
        by_strategy = {}
        sessions = {}
        total = 0
        for line in log_path.read_text(encoding="utf-8", errors="replace").splitlines():
            match = LINE_RE.search(line)
            if not match:
                continue
            session, strategy, tier = match.group("session"), match.group("strategy"), match.group("tier")
            if wanted is not None and session not in wanted:
                continue
            total += 1
            bucket = by_strategy.setdefault(strategy, {"decisions": 0, "tiers": {}, "margins": []})
            bucket["decisions"] += 1
            bucket["tiers"][tier] = bucket["tiers"].get(tier, 0) + 1
            margin = match.group("margin")
            try:
                bucket["margins"].append(float(margin))
            except ValueError:
                pass  # 'n/a' for forced strategies
            sessions.setdefault(session, []).append({"strategy": strategy, "tier": tier})
        for bucket in by_strategy.values():
            margins = bucket.pop("margins")
            bucket["margin_mean"] = round(sum(margins) / len(margins), 4) if margins else None
            bucket["margin_n"] = len(margins)
        result = {
            "area": "E3-tiers",
            "status": "measured" if total else "not_found",
            "method": "Parsed backend log lines 'event=routing_decision'. "
            "This is the only source for the tier actually used under HYBRID, "
            "because the chat response carries no tier field.",
            "decisions_parsed": total,
            "by_strategy": by_strategy,
            "sessions_seen": len(sessions),
        }
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(result, indent=2, ensure_ascii=False), encoding="utf-8")
    print(f"E3 tiers: status={result['status']} decisions={result.get('decisions_parsed', 0)} -> {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
