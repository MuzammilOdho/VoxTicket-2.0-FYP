"""P4.1: outbound queue poller - the missing link between the Java QUEUED
rows and the worker SIP dialer.

Covers: API base derivation, shared-secret headers, one poll pass against
a fake HTTP transport (pending -> DIALING -> dial -> COMPLETED asserted in
order), dial failure -> FAILED with the loop continuing, empty queue ->
no dials, a Java-rejected DIALING mark (400) skipping the dial, and CLI
config/arg exit codes.
"""

import asyncio
import json

import httpx
import pytest

import outbound_poller
from outbound_poller import (
    api_base,
    main,
    parse_args,
    poll_once,
    secret_headers,
)
from outbound import require_outbound_config
from agent import ConfigError, load_config

FULL_ENV = {
    "LIVEKIT_URL": "ws://localhost:7880",
    "LIVEKIT_API_KEY": "devkey",
    "LIVEKIT_API_SECRET": "secret",
    "ASSEMBLYAI_API_KEY": "aai-key",
    "CARTESIA_API_KEY": "cart-key",
    "CARTESIA_VOICE_EN": "voice-en-id",
    "CARTESIA_VOICE_UR": "voice-ur-id",
    "SIP_TRUNK_ID": "trunk-123",
    "VOICE_TELEMETRY_SECRET": "tele-secret",
}


def _cfg(**overrides):
    env = dict(FULL_ENV)
    env.update(overrides)
    return load_config(env)


def _call(call_id, phone, reason="DELIVERY_UPDATE"):
    return {
        "callId": call_id,
        "customerPhone": phone,
        "reason": reason,
        "status": "QUEUED",
    }


class _FakeJava:
    """Fake Java outbound API over httpx.MockTransport.

    Records PATCH (call_id, status) in order. DIALING marks for call ids
    in `reject_dialing_for` return 400 (illegal transition / unknown call).
    """

    def __init__(self, pending, reject_dialing_for=()):
        self.pending = pending
        self.reject_dialing_for = set(reject_dialing_for)
        self.patches = []
        self.gets = 0

    def __call__(self, request):
        path = request.url.path
        if request.method == "GET" and path.endswith("/outbound/pending"):
            self.gets += 1
            return httpx.Response(200, json=self.pending)
        if (request.method == "PATCH" and "/outbound/" in path
                and path.endswith("/status")):
            call_id = path.split("/outbound/")[1].split("/")[0]
            status = json.loads(request.content)["status"]
            self.patches.append((call_id, status))
            if status == "DIALING" and call_id in self.reject_dialing_for:
                return httpx.Response(400, json={"error": "illegal transition"})
            return httpx.Response(200, json={})
        return httpx.Response(404, json={"error": "not found"})


def _run(coro):
    return asyncio.run(coro)


# ---- URL + headers ----

def test_api_base_strips_path_after_api():
    assert api_base("http://localhost:8080/api/v1/voice/turn") == "http://localhost:8080"


def test_api_base_falls_back_without_api_segment():
    assert api_base("http://localhost:8080/") == "http://localhost:8080"


def test_secret_headers_match_worker_telemetry_posture():
    cfg = _cfg()
    assert secret_headers(cfg) == {"X-VoxTicket-Telemetry-Secret": "tele-secret"}


def test_secret_headers_empty_without_secret():
    cfg = _cfg(VOICE_TELEMETRY_SECRET="")
    assert secret_headers(cfg) == {}


# ---- poll passes ----

def test_poll_once_happy_path_patches_in_order():
    java = _FakeJava([
        _call("call-1", "+15550001111"),
        _call("call-2", "+15550002222", reason="FOLLOW_UP"),
    ])
    dialed = []

    async def fake_dial(cfg, number, reason):
        dialed.append((number, reason))

    async def go():
        async with httpx.AsyncClient(transport=httpx.MockTransport(java)) as client:
            return await poll_once(_cfg(), client, "http://localhost:8080",
                                   secret_headers(_cfg()), dial_fn=fake_dial)

    assert _run(go()) == 2
    assert dialed == [("+15550001111", "DELIVERY_UPDATE"),
                      ("+15550002222", "FOLLOW_UP")]
    assert java.patches == [("call-1", "DIALING"), ("call-1", "COMPLETED"),
                            ("call-2", "DIALING"), ("call-2", "COMPLETED")]


def test_poll_once_dial_failure_marks_failed_and_continues():
    java = _FakeJava([
        _call("call-1", "+15550001111"),
        _call("call-2", "+15550002222"),
    ])
    dialed = []

    async def fake_dial(cfg, number, reason):
        dialed.append(number)
        if number == "+15550001111":
            raise RuntimeError("SIP dial exploded")

    async def go():
        async with httpx.AsyncClient(transport=httpx.MockTransport(java)) as client:
            return await poll_once(_cfg(), client, "http://localhost:8080",
                                   secret_headers(_cfg()), dial_fn=fake_dial)

    assert _run(go()) == 2
    assert dialed == ["+15550001111", "+15550002222"]
    assert java.patches == [("call-1", "DIALING"), ("call-1", "FAILED"),
                            ("call-2", "DIALING"), ("call-2", "COMPLETED")]


def test_poll_once_empty_queue_no_dials():
    java = _FakeJava([])
    dialed = []

    async def fake_dial(cfg, number, reason):
        dialed.append(number)

    async def go():
        async with httpx.AsyncClient(transport=httpx.MockTransport(java)) as client:
            return await poll_once(_cfg(), client, "http://localhost:8080",
                                   {}, dial_fn=fake_dial)

    assert _run(go()) == 0
    assert dialed == []
    assert java.patches == []
    assert java.gets == 1


def test_poll_once_rejected_dialing_mark_skips_dial():
    # Java says 400 on the DIALING mark (e.g. the call was dequeued
    # elsewhere): the poller must NOT dial, and must continue with the
    # next call.
    java = _FakeJava([
        _call("call-1", "+15550001111"),
        _call("call-2", "+15550002222"),
    ], reject_dialing_for={"call-1"})
    dialed = []

    async def fake_dial(cfg, number, reason):
        dialed.append(number)

    async def go():
        async with httpx.AsyncClient(transport=httpx.MockTransport(java)) as client:
            return await poll_once(_cfg(), client, "http://localhost:8080",
                                   {}, dial_fn=fake_dial)

    assert _run(go()) == 2
    assert dialed == ["+15550002222"]
    assert java.patches == [("call-1", "DIALING"),
                            ("call-2", "DIALING"), ("call-2", "COMPLETED")]


def test_poll_once_malformed_entry_skipped():
    java = _FakeJava([{"callId": "", "customerPhone": ""},
                      _call("call-2", "+15550002222")])
    dialed = []

    async def fake_dial(cfg, number, reason):
        dialed.append(number)

    async def go():
        async with httpx.AsyncClient(transport=httpx.MockTransport(java)) as client:
            return await poll_once(_cfg(), client, "http://localhost:8080",
                                   {}, dial_fn=fake_dial)

    assert _run(go()) == 1
    assert dialed == ["+15550002222"]


# ---- CLI / config ----

def test_parse_args_defaults():
    args = parse_args([])
    assert args.interval == 15.0
    assert args.once is False


def test_main_rejects_nonpositive_interval():
    assert main(["--interval", "0"]) == 2
    assert main(["--interval", "-5"]) == 2


def test_main_missing_trunk_is_config_error(monkeypatch):
    cfg = load_config({k: v for k, v in FULL_ENV.items() if k != "SIP_TRUNK_ID"})
    monkeypatch.setattr(outbound_poller, "load_config", lambda: cfg)
    assert main(["--once"]) == 2


def test_main_missing_env_is_config_error(monkeypatch):
    def boom():
        raise KeyError("LIVEKIT_URL")
    monkeypatch.setattr(outbound_poller, "load_config", boom)
    assert main(["--once"]) == 2


def test_require_outbound_config_still_fail_fast():
    cfg = load_config({k: v for k, v in FULL_ENV.items() if k != "SIP_TRUNK_ID"})
    with pytest.raises(ConfigError):
        require_outbound_config(cfg)
