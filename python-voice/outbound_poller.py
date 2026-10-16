#!/usr/bin/env python3
"""VoxTicket outbound queue poller (P4.1 integration).

Closes the gap between the Java outbound queue and the worker dialer:
`POST /api/v1/voice/outbound` persists QUEUED rows in Java, and
`outbound.py` dials one number directly - nothing connected the two.
This poller is the missing link. It runs as a sidecar next to
`agent.py start`:

    python outbound_poller.py --interval 15     # loop forever (systemd)
    python outbound_poller.py --once            # single pass (cron)

Each pass: GET the QUEUED queue (oldest first), PATCH each call to DIALING,
dial it via SIP (outbound.place_outbound_call), then PATCH COMPLETED on
success or FAILED on any dial error. A per-call failure is reported and the
pass continues - one bad number never kills the loop.

Privacy: phone numbers are never logged at INFO or above (call ids only);
full details go to DEBUG.

Auth: the same shared-secret header as the worker's telemetry path
(agent.py TelemetryBuffer._secret_headers). The outbound endpoints are
permitAll today (same posture as the other voice endpoints), so the header
is a no-op there - it future-proofs the poller if the API tightens auth.

Exit codes: 0 = pass completed (per-call failures are reported as FAILED,
never fatal), 2 = config/argument error.
"""

from __future__ import annotations

import argparse
import asyncio
import logging
import sys
from typing import Any, Awaitable, Callable

import httpx

from agent import Config, ConfigError, load_config
from outbound import place_outbound_call, require_outbound_config

logger = logging.getLogger("voxticket-outbound-poller")

POLL_PATH = "/api/v1/voice/outbound/pending"
STATUS_PATH_TEMPLATE = "/api/v1/voice/outbound/{call_id}/status"

# Same shared-secret header as the worker telemetry path (agent.py).
TELEMETRY_SECRET_HEADER = "X-VoxTicket-Telemetry-Secret"


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    """CLI: outbound_poller.py [--interval SECONDS] [--once]."""
    parser = argparse.ArgumentParser(
        description="Poll the Java outbound queue and dial QUEUED calls via SIP.",
    )
    parser.add_argument(
        "--interval",
        type=float,
        default=15.0,
        help="Seconds between poll passes (default: 15).",
    )
    parser.add_argument(
        "--once",
        action="store_true",
        help="Run a single poll pass and exit (for cron/systemd).",
    )
    return parser.parse_args(argv)


def api_base(turn_url: str) -> str:
    """Derive the API base from VOXTICKET_TURN_URL.

    Strips the path after /api, so
    "http://localhost:8080/api/v1/voice/turn" -> "http://localhost:8080".
    Falls back to the whole URL (rstrip '/') when there is no /api segment.
    Never raises.
    """
    try:
        url = (turn_url or "").strip()
        idx = url.find("/api")
        base = url[:idx] if idx > 0 else url
        return base.rstrip("/") or url
    except Exception:
        return turn_url


def secret_headers(cfg: Config) -> dict[str, str]:
    """The shared-secret header, exactly like the worker telemetry path.

    Empty dict when no secret is configured (headers=None equivalent) -
    a missing secret never fails the poll.
    """
    secret = (cfg.voice_telemetry_secret or "").strip()
    return {TELEMETRY_SECRET_HEADER: secret} if secret else {}


async def fetch_pending(
    client: httpx.AsyncClient, base_url: str, headers: dict[str, str], limit: int = 10
) -> list[dict[str, Any]]:
    """GET the QUEUED queue. Raises on transport/HTTP errors (the caller
    decides whether the pass survives); returns [] for a non-list body."""
    resp = await client.get(
        f"{base_url}{POLL_PATH}", params={"limit": limit}, headers=headers or None
    )
    resp.raise_for_status()
    data = resp.json()
    return data if isinstance(data, list) else []


async def report_status(
    client: httpx.AsyncClient,
    base_url: str,
    headers: dict[str, str],
    call_id: str,
    status: str,
) -> None:
    """PATCH one call's status. Raises on transport/HTTP errors so the
    caller can log-and-continue (a stale/unknown callId is the worker's
    problem, not the loop's)."""
    resp = await client.patch(
        f"{base_url}{STATUS_PATH_TEMPLATE.format(call_id=call_id)}",
        json={"status": status},
        headers=headers or None,
    )
    resp.raise_for_status()


async def poll_once(
    cfg: Config,
    client: httpx.AsyncClient,
    base_url: str,
    headers: dict[str, str],
    *,
    limit: int = 10,
    dial_fn: Callable[[Config, str, str], Awaitable[None]] | None = None,
) -> int:
    """Run one poll pass. Returns the number of calls attempted.

    For each QUEUED call: mark DIALING, dial, then mark COMPLETED/FAILED.
    Per-call failures (mark or dial) are logged and the pass continues -
    a failed PATCH never aborts the remaining calls. Phone numbers are
    never logged above DEBUG (call ids only).
    """
    dial = dial_fn or (lambda c, number, reason: place_outbound_call(
        c, number, reason, redial=True))
    pending = await fetch_pending(client, base_url, headers, limit)
    if not pending:
        logger.debug("outbound poll: queue empty")
        return 0
    logger.info("outbound poll: %d queued call(s)", len(pending))
    attempted = 0
    for item in pending:
        call_id = str(item.get("callId") or "")
        number = str(item.get("customerPhone") or "")
        reason = str(item.get("reason") or "DELIVERY_UPDATE")
        if not call_id or not number:
            logger.warning("outbound poll: skipping malformed queue entry (callId present=%s)",
                           bool(call_id))
            continue
        attempted += 1
        try:
            await report_status(client, base_url, headers, call_id, "DIALING")
        except Exception as exc:
            # A rejected DIALING mark (e.g. the call was dequeued elsewhere)
            # means we must not dial: skip to the next call.
            logger.warning("outbound poll: DIALING mark rejected for call %s (%s); skipping",
                           call_id, type(exc).__name__)
            continue
        try:
            await dial(cfg, number, reason)
        except Exception as exc:
            logger.warning("outbound dial failed for call %s (%s)",
                           call_id, type(exc).__name__)
            logger.debug("outbound dial failure detail for call %s", call_id, exc_info=True)
            try:
                await report_status(client, base_url, headers, call_id, "FAILED")
            except Exception:
                logger.warning("outbound poll: FAILED mark rejected for call %s", call_id)
            continue
        try:
            await report_status(client, base_url, headers, call_id, "COMPLETED")
        except Exception:
            logger.warning("outbound poll: COMPLETED mark rejected for call %s", call_id)
        logger.info("outbound poll: call %s completed", call_id)
    return attempted


async def run_loop(cfg: Config, interval: float, once: bool) -> int:
    """The poller main loop. A failed pass is logged, never fatal."""
    base_url = api_base(cfg.voxticket_turn_url)
    headers = secret_headers(cfg)
    logger.info("outbound poller started: base=%s interval=%ss once=%s",
                base_url, interval, once)
    async with httpx.AsyncClient(timeout=30.0) as client:
        if once:
            await poll_once(cfg, client, base_url, headers)
            return 0
        while True:
            try:
                await poll_once(cfg, client, base_url, headers)
            except Exception as exc:
                # Transport/HTTP failure on the poll itself: back off and
                # keep the loop alive.
                logger.warning("outbound poll pass failed (%s); retrying in %ss",
                               type(exc).__name__, interval)
            await asyncio.sleep(interval)
    # unreachable


def main(argv: list[str] | None = None) -> int:
    """CLI entrypoint. Returns the process exit code (0/2)."""
    logging.basicConfig(level=logging.INFO)
    args = parse_args(argv)
    if args.interval <= 0:
        print("error: --interval must be positive", file=sys.stderr)
        return 2
    try:
        cfg = load_config()
        require_outbound_config(cfg)  # fail-fast: no trunk, no polling
    except (ConfigError, KeyError) as exc:
        # KeyError: a required env var (e.g. LIVEKIT_URL) is absent -
        # also a config error, not a crash.
        print(f"error: {exc}", file=sys.stderr)
        return 2
    return asyncio.run(run_loop(cfg, args.interval, args.once))


if __name__ == "__main__":
    raise SystemExit(main())
