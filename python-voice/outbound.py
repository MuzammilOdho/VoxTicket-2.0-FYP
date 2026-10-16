#!/usr/bin/env python3
"""VoxTicket outbound dial-out (P4.1).

Opt-in, single-call dial-out over LiveKit SIP. Dials ONE number, waits for
the answer, then exits. No campaigns, no retries beyond one immediate redial.

The already-running voice worker (`agent.py start`) picks the call up: the
dialed SIP participant joins a fresh room, the worker's SIP dispatch rule
routes the room to it, and the agent greets with the outbound reason context
instead of the default greeting (see `parse_outbound_context` in agent.py -
the reason travels in the room metadata minted here).

Usage:
    python outbound.py +15551234567 --reason DELIVERY_UPDATE
    python outbound.py "+92 300 1234567" --reason APPOINTMENT_REMINDER --no-redial

Exit codes: 0 = dialed and answered (or at least dispatched), 2 = argument /
validation error (bad number), anything else = operational failure
(ConfigError for a missing SIP_TRUNK_ID, SIP dial errors after the redial).

Env: the same Config as agent.py (LIVEKIT_URL / LIVEKIT_API_KEY /
LIVEKIT_API_SECRET plus provider keys). SIP_TRUNK_ID is required and
fail-fast: without it this exits via ConfigError before touching the API.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import logging
import sys
import uuid
from typing import Callable

from livekit import api as livekit_api

from agent import (
    Config,
    ConfigError,
    load_config,
    normalize_e164,
)

logger = logging.getLogger("voxticket-outbound")

# Room names for outbound calls, so the worker can tell them apart in logs.
ROOM_PREFIX = "outbound-"

# Known outbound reasons -> the greeting the agent speaks when the callee
# answers. Unknown reasons fall back to the generic template below; the
# greeting is carried in the room metadata (never in the dial request),
# so adding a reason is a one-line change here.
OUTBOUND_GREETINGS: dict[str, str] = {
    "DELIVERY_UPDATE": (
        "Hi, this is VoxTicket calling with a delivery update. "
        "Do you have a moment to talk about your order?"
    ),
    "APPOINTMENT_REMINDER": (
        "Hi, this is VoxTicket calling to remind you about your upcoming appointment. "
        "Is now a good time?"
    ),
    "PAYMENT_REMINDER": (
        "Hi, this is VoxTicket calling about a pending payment on your account. "
        "Do you have a moment?"
    ),
    "FEEDBACK_REQUEST": (
        "Hi, this is VoxTicket. We'd love a moment of your time for quick feedback "
        "on your recent experience. Is now a good time?"
    ),
}

# Key in the room metadata JSON carrying the outbound context. agent.py's
# entrypoint reads exactly this key (see parse_outbound_context).
OUTBOUND_METADATA_KEY = "voxticket_outbound"


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    """CLI: outbound.py <number> [--reason R] [--room NAME] [--no-redial]."""
    parser = argparse.ArgumentParser(
        description="Dial one phone number via LiveKit SIP (VoxTicket outbound).",
    )
    parser.add_argument(
        "number",
        help="Destination phone number (E.164, e.g. +15551234567).",
    )
    parser.add_argument(
        "--reason",
        default="DELIVERY_UPDATE",
        help="Outbound reason context for the greeting (default: DELIVERY_UPDATE).",
    )
    parser.add_argument(
        "--room",
        default=None,
        help="Room name to dial into (default: minted outbound-<uuid>).",
    )
    parser.add_argument(
        "--no-redial",
        action="store_true",
        help="Do not attempt the single redial on immediate dial failure.",
    )
    return parser.parse_args(argv)


def normalize_outbound_number(raw: str) -> str:
    """Validate a dial destination. Returns the E.164 number.

    Raises ValueError (never returns None) - a dial target must be explicit,
    unlike the inbound caller identity which may degrade to unknown.
    """
    normalized = normalize_e164(raw)
    if normalized is None:
        raise ValueError(
            f"not a valid E.164 phone number: {raw!r} "
            "(expected e.g. +15551234567)"
        )
    return normalized


def require_outbound_config(cfg: Config) -> str:
    """Fail fast when SIP dial-out is not configured.

    Returns the trunk id. Raises ConfigError naming SIP_TRUNK_ID when it is
    empty - outbound dialing without a trunk is a configuration error, not
    a runtime surprise.
    """
    trunk = (cfg.sip_trunk_id or "").strip()
    if not trunk:
        raise ConfigError(
            "SIP_TRUNK_ID is not set: outbound dial-out requires a LiveKit "
            "SIP trunk (see python-voice/docs/SIP_SETUP.md)"
        )
    return trunk


def outbound_greeting(reason: str) -> str:
    """The agent's greeting for an outbound call with the given reason."""
    known = OUTBOUND_GREETINGS.get((reason or "").strip().upper())
    if known:
        return known
    return (
        f"Hi, this is VoxTicket calling about {reason}. "
        "Do you have a moment?"
    )


def outbound_room_metadata(number: str, reason: str) -> str:
    """Room metadata JSON carrying the outbound context for the worker.

    The agent entrypoint parses OUTBOUND_METADATA_KEY (see
    agent.parse_outbound_context): the reason selects the greeting and the
    agent waits for the callee before speaking.
    """
    return json.dumps({
        OUTBOUND_METADATA_KEY: {
            "reason": reason,
            "to": number,
            "greeting": outbound_greeting(reason),
        },
    })


async def place_outbound_call(
    cfg: Config,
    number: str,
    reason: str,
    *,
    room_name: str | None = None,
    redial: bool = True,
    api_factory: Callable[[], livekit_api.LiveKitAPI] | None = None,
) -> str:
    """Dial one number via SIP and wait for the answer. Returns the room name.

    Creates a fresh room carrying the outbound context in its metadata, then
    dials the number into it with wait_until_answered=True so this returns
    once the callee picks up. On immediate dial failure one redial is
    attempted (unless redial=False); a second failure raises.

    api_factory is an injection point for tests; production builds the real
    LiveKitAPI from the config.
    """
    trunk = require_outbound_config(cfg)
    room = room_name or f"{ROOM_PREFIX}{uuid.uuid4().hex[:12]}"
    metadata = outbound_room_metadata(number, reason)

    lk = api_factory() if api_factory is not None else livekit_api.LiveKitAPI(
        cfg.livekit_url, cfg.livekit_api_key, cfg.livekit_api_secret
    )
    try:
        await lk.room.create_room(livekit_api.CreateRoomRequest(
            name=room,
            metadata=metadata,
            empty_timeout=600,
            max_participants=2,
        ))
        logger.info("outbound room created: %s", room)

        def _dial_request() -> livekit_api.CreateSIPParticipantRequest:
            return livekit_api.CreateSIPParticipantRequest(
                sip_call_to=number,
                room_name=room,
                participant_identity=number,
                participant_name=f"VoxTicket outbound ({reason})",
                wait_until_answered=True,
                play_ringtone=True,
            )

        attempts = 2 if redial else 1
        last_error: Exception | None = None
        for attempt in range(1, attempts + 1):
            try:
                info = await lk.sip.create_sip_participant(
                    _dial_request(),
                    trunk_id=trunk,
                )
                logger.info(
                    "outbound dial answered: to=%s room=%s participant=%s",
                    number, room, info.participant_id,
                )
                return room
            except Exception as e:
                last_error = e
                logger.warning(
                    "outbound dial attempt %d/%d failed: %s",
                    attempt, attempts, e,
                )
        raise RuntimeError(
            f"outbound dial to {number} failed after {attempts} attempt(s): {last_error}"
        ) from last_error
    finally:
        await lk.aclose()


def main(argv: list[str] | None = None) -> int:
    """CLI entrypoint. Returns the process exit code (0/2)."""
    logging.basicConfig(level=logging.INFO)
    args = parse_args(argv)

    try:
        number = normalize_outbound_number(args.number)
    except ValueError as e:
        print(f"error: {e}", file=sys.stderr)
        return 2

    cfg = load_config()
    require_outbound_config(cfg)  # ConfigError propagates (fail-fast, like agent.py)

    room = asyncio.run(place_outbound_call(
        cfg, number, args.reason,
        room_name=args.room,
        redial=not args.no_redial,
    ))
    print(f"dialed {number} (reason={args.reason}) into room {room}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
