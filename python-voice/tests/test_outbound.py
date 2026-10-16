"""P4.1: outbound dial-out (worker CLI mode).

Covers: arg parsing, E.164 validation (bad number -> exit 2), fail-fast
SIP_TRUNK_ID (ConfigError), greeting selection per reason, room metadata
contract with the worker (parse_outbound_context round-trip), and the dial
path with a fake LiveKit API (success, one redial on immediate failure,
second failure raises, --no-redial disables the retry).
"""

import json
from types import SimpleNamespace

import pytest

import outbound
from outbound import (
    normalize_outbound_number,
    outbound_greeting,
    outbound_room_metadata,
    parse_args,
    place_outbound_call,
    require_outbound_config,
)
from agent import ConfigError, load_config, parse_outbound_context

FULL_ENV = {
    "LIVEKIT_URL": "ws://localhost:7880",
    "LIVEKIT_API_KEY": "devkey",
    "LIVEKIT_API_SECRET": "secret",
    "ASSEMBLYAI_API_KEY": "aai-key",
    "CARTESIA_API_KEY": "cart-key",
    "CARTESIA_VOICE_EN": "voice-en-id",
    "CARTESIA_VOICE_UR": "voice-ur-id",
}


def _cfg_with_trunk():
    return load_config({**FULL_ENV, "SIP_TRUNK_ID": "trunk-123"})


# --- arg parsing -----------------------------------------------------------

def test_parse_args_defaults():
    args = parse_args(["+15551234567"])
    assert args.number == "+15551234567"
    assert args.reason == "DELIVERY_UPDATE"
    assert args.room is None
    assert args.no_redial is False


def test_parse_args_overrides():
    args = parse_args(["+15551234567", "--reason", "APPOINTMENT_REMINDER",
                       "--room", "r1", "--no-redial"])
    assert args.reason == "APPOINTMENT_REMINDER"
    assert args.room == "r1"
    assert args.no_redial is True


# --- number validation -----------------------------------------------------

def test_normalize_outbound_number_valid():
    assert normalize_outbound_number("+15551234567") == "+15551234567"
    assert normalize_outbound_number("1555 123 4567") == "+15551234567"


def test_normalize_outbound_number_invalid_raises():
    with pytest.raises(ValueError):
        normalize_outbound_number("not-a-number")
    with pytest.raises(ValueError):
        normalize_outbound_number("")
    with pytest.raises(ValueError):
        normalize_outbound_number("+12")  # too short


def test_main_bad_number_exit_2(capsys):
    assert outbound.main(["not-a-number"]) == 2
    assert "error" in capsys.readouterr().err.lower()


# --- config fail-fast ------------------------------------------------------

def test_require_outbound_config_missing_trunk():
    cfg = load_config(dict(FULL_ENV))  # no SIP_TRUNK_ID
    with pytest.raises(ConfigError) as exc:
        require_outbound_config(cfg)
    assert "SIP_TRUNK_ID" in str(exc.value)


def test_require_outbound_config_returns_trunk():
    assert require_outbound_config(_cfg_with_trunk()) == "trunk-123"


# --- greeting selection ----------------------------------------------------

def test_outbound_greeting_known_reason():
    g = outbound_greeting("DELIVERY_UPDATE")
    assert "delivery" in g.lower()
    assert "VoxTicket" in g


def test_outbound_greeting_case_insensitive():
    assert outbound_greeting("delivery_update") == outbound_greeting("DELIVERY_UPDATE")


def test_outbound_greeting_unknown_reason_generic():
    g = outbound_greeting("MYSTERY_REASON")
    assert "MYSTERY_REASON" in g
    assert "VoxTicket" in g


# --- room metadata contract with the worker --------------------------------

def test_outbound_room_metadata_shape():
    meta = json.loads(outbound_room_metadata("+15551234567", "DELIVERY_UPDATE"))
    ctx = meta["voxticket_outbound"]
    assert ctx["reason"] == "DELIVERY_UPDATE"
    assert ctx["to"] == "+15551234567"
    assert "delivery" in ctx["greeting"].lower()


def test_metadata_keys_match_agent():
    assert outbound.OUTBOUND_METADATA_KEY == "voxticket_outbound"


def test_parse_outbound_context_round_trip():
    meta = outbound_room_metadata("+15551234567", "PAYMENT_REMINDER")
    ctx = parse_outbound_context(meta)
    assert ctx is not None
    assert ctx["reason"] == "PAYMENT_REMINDER"
    assert ctx["to"] == "+15551234567"
    assert "payment" in ctx["greeting"].lower()


def test_parse_outbound_context_rejects():
    assert parse_outbound_context(None) is None
    assert parse_outbound_context("") is None
    assert parse_outbound_context("not json") is None
    assert parse_outbound_context(json.dumps({"other": 1})) is None
    assert parse_outbound_context(json.dumps({"voxticket_outbound": {"reason": "X"}})) is None


# --- dial path (fake LiveKit API) ------------------------------------------

class _FakeSip:
    def __init__(self, fail_times=0):
        self.fail_times = fail_times
        self.calls = 0
        self.last_trunk_id = None

    async def create_sip_participant(self, req, *, trunk_id=None):
        self.calls += 1
        self.last_trunk_id = trunk_id
        assert req.sip_call_to == "+15551234567"
        assert req.wait_until_answered is True
        if self.calls <= self.fail_times:
            raise RuntimeError("SIP 486 Busy Here")
        return SimpleNamespace(participant_id="part-1")


class _FakeRoomSvc:
    def __init__(self):
        self.created = []

    async def create_room(self, req):
        self.created.append(req)
        return SimpleNamespace(name=req.name)


class _FakeApi:
    def __init__(self, fail_times=0):
        self.room = _FakeRoomSvc()
        self.sip = _FakeSip(fail_times)
        self.closed = False

    async def aclose(self):
        self.closed = True


def _run(coro):
    import asyncio
    return asyncio.run(coro)


def test_dial_success_returns_room():
    fake = _FakeApi()
    room = _run(place_outbound_call(
        _cfg_with_trunk(), "+15551234567", "DELIVERY_UPDATE",
        room_name="outbound-test", api_factory=lambda: fake,
    ))
    assert room == "outbound-test"
    assert fake.sip.calls == 1
    assert fake.sip.last_trunk_id == "trunk-123"
    assert fake.closed is True
    # room carried the outbound context for the worker
    ctx = parse_outbound_context(fake.room.created[0].metadata)
    assert ctx is not None and ctx["reason"] == "DELIVERY_UPDATE"


def test_dial_redials_once_on_immediate_failure():
    fake = _FakeApi(fail_times=1)
    room = _run(place_outbound_call(
        _cfg_with_trunk(), "+15551234567", "DELIVERY_UPDATE",
        room_name="outbound-test", api_factory=lambda: fake,
    ))
    assert room == "outbound-test"
    assert fake.sip.calls == 2


def test_dial_second_failure_raises():
    fake = _FakeApi(fail_times=5)
    with pytest.raises(RuntimeError):
        _run(place_outbound_call(
            _cfg_with_trunk(), "+15551234567", "DELIVERY_UPDATE",
            room_name="outbound-test", api_factory=lambda: fake,
        ))
    assert fake.sip.calls == 2


def test_dial_no_redial_single_attempt():
    fake = _FakeApi(fail_times=1)
    with pytest.raises(RuntimeError):
        _run(place_outbound_call(
            _cfg_with_trunk(), "+15551234567", "DELIVERY_UPDATE",
            room_name="outbound-test", redial=False, api_factory=lambda: fake,
        ))
    assert fake.sip.calls == 1


def test_dial_mints_room_name_when_omitted():
    fake = _FakeApi()
    room = _run(place_outbound_call(
        _cfg_with_trunk(), "+15551234567", "DELIVERY_UPDATE",
        api_factory=lambda: fake,
    ))
    assert room.startswith("outbound-")
