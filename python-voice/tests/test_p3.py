"""P3: SIP telephony wiring, recording hooks, OTP redaction.

Covers: E.164 normalization (strip formatting, enforce leading +,
reject garbage with a clear path to "unknown"), OTP masking in free
text and in the telemetry flush payload, SIP-disabled inertness (empty
SIP_TRUNK_ID = byte-for-byte the WebRTC demo behavior), and
fail-fast recording config.
"""

import json

import httpx
import pytest

from agent import (
    ConfigError,
    TurnTelemetry,
    _start_call_recording,
    load_config,
    normalize_e164,
    redact_otp,
    redact_telemetry_payload,
    telephony_metadata,
)

FULL_ENV = {
    "LIVEKIT_URL": "ws://localhost:7880",
    "LIVEKIT_API_KEY": "devkey",
    "LIVEKIT_API_SECRET": "secret",
    "ASSEMBLYAI_API_KEY": "aai-key",
    "CARTESIA_API_KEY": "cart-key",
    "CARTESIA_VOICE_EN": "voice-en-id",
    "CARTESIA_VOICE_UR": "voice-ur-id",
}


# --- E.164 normalization -------------------------------------------------

def test_normalize_e164_plain_digits_gain_plus():
    assert normalize_e164("15551234567") == "+15551234567"


def test_normalize_e164_strips_formatting():
    assert normalize_e164(" +1 555-123-4567 ") == "+15551234567"
    assert normalize_e164("+92 (300) 123-4567") == "+923001234567"


def test_normalize_e164_accepts_valid():
    assert normalize_e164("+15551234567") == "+15551234567"
    assert normalize_e164("+923001234567") == "+923001234567"


def test_normalize_e164_rejects_garbage(caplog):
    assert normalize_e164("anonymous") is None
    assert normalize_e164("sip:alice@example.com") is None
    assert normalize_e164(" withheld ") is None
    assert "non-E.164 caller identity" in caplog.text


def test_normalize_e164_rejects_bad_lengths():
    assert normalize_e164("+123456") is None          # 6 digits: too short
    assert normalize_e164("+1234567890123456") is None  # 16 digits: too long
    assert normalize_e164("+1234567") == "+1234567"   # 7: minimum ok
    assert normalize_e164("+123456789012345") == "+123456789012345"  # 15: max ok


def test_normalize_e164_empty_is_none_not_warning(caplog):
    assert normalize_e164(None) is None
    assert normalize_e164("") is None
    assert normalize_e164("   ") is None
    assert "non-E.164 caller identity" not in caplog.text


# --- OTP redaction ---------------------------------------------------------

def test_redact_otp_masks_4_to_8_digits():
    assert redact_otp("my code is 482913") == "my code is ****"
    assert redact_otp("1234") == "****"
    assert redact_otp("12345678") == "****"


def test_redact_otp_leaves_short_and_long_runs():
    assert redact_otp("call 123") == "call 123"          # 3 digits: untouched
    assert redact_otp("order 123456789") == "order 123456789"  # 9: untouched
    # A 6-digit span inside a longer digit run is not a standalone OTP.
    assert redact_otp("id 1234567890") == "id 1234567890"


def test_redact_otp_multiple_and_mixed():
    assert redact_otp("4829 then 482913 ok") == "**** then **** ok"
    assert redact_otp("no digits here") == "no digits here"
    assert redact_otp("") == ""
    assert redact_otp(None) is None


def test_redact_telemetry_payload_walks_nested():
    payload = {
        "workerId": "w1",
        "turns": [{"turnNumber": 1, "note": "caller said 482913", "ok": True}],
        "count": 482913,  # numeric fields pass through untouched
    }
    redacted = redact_telemetry_payload(payload)
    assert redacted["turns"][0]["note"] == "caller said ****"
    assert redacted["turns"][0]["ok"] is True
    assert redacted["count"] == 482913
    # The original payload is not mutated.
    assert payload["turns"][0]["note"] == "caller said 482913"


def test_redact_telemetry_payload_spares_machine_fields():
    """Machine-generated values must not be mangled: a hex trace id can
    contain 4+ digit runs and ISO dates contain a 4-digit year - neither
    is caller speech."""
    payload = {
        "traceId": "abc12345def67890abc12345def67890",
        "startedAt": "2026-10-06T18:00:00+00:00",
        "room": "sip-+15551234567",
    }
    redacted = redact_telemetry_payload(payload)
    assert redacted == payload


@pytest.mark.asyncio
async def test_flush_masks_otp_before_post():
    """End-to-end at the flush choke point: an OTP-like span in a turn's
    serialized dict must never reach the wire."""
    from agent import TelemetryBuffer

    posted = {}

    def handler(request: httpx.Request) -> httpx.Response:
        posted["json"] = json.loads(request.content)
        return httpx.Response(202)

    buf = TelemetryBuffer(
        worker_id="w1",
        telemetry_url="http://java:8080/api/v1/voice/telemetry",
        heartbeat_url="http://java:8080/api/v1/voice/worker-heartbeat",
        client=httpx.AsyncClient(transport=httpx.MockTransport(handler)),
        flush_seconds=60.0,
    )
    timing = TurnTelemetry(
        room="room-9", turn_number=1, trace_id="t" * 32, span_id="s" * 16,
        stt_provider="assemblyai", stt_model="m",
        tts_provider="cartesia", tts_model="s3",
    )
    timing.finalize()
    # Simulate a future free-text field carrying a dictated OTP.
    timing.to_dict = lambda: {"turnNumber": 1, "transcriptNote": "otp 482913"}
    buf.record_turn(timing)

    await buf._flush_once()

    body = posted["json"]["turns"][0]
    assert body["transcriptNote"] == "otp ****"
    assert "482913" not in json.dumps(posted["json"])


# --- SIP-disabled inertness -------------------------------------------------

def test_sip_disabled_by_default_is_inert():
    cfg = load_config(dict(FULL_ENV))
    assert cfg.sip_trunk_id == ""
    assert cfg.sip_dispatch_rule == ""
    assert telephony_metadata(cfg) == {}


def test_sip_enabled_carries_trunk_metadata():
    cfg = load_config(dict(FULL_ENV, SIP_TRUNK_ID="ST_abc123",
                           SIP_DISPATCH_RULE="voxticket-inbound"))
    meta = telephony_metadata(cfg)
    assert meta == {"telephony": "sip", "sip_trunk_id": "ST_abc123",
                    "sip_dispatch_rule": "voxticket-inbound"}


def test_sip_trunk_without_rule_still_works():
    cfg = load_config(dict(FULL_ENV, SIP_TRUNK_ID="ST_abc123"))
    assert telephony_metadata(cfg)["sip_dispatch_rule"] == ""


# --- Recording config -------------------------------------------------------

def test_recording_disabled_by_default():
    cfg = load_config(dict(FULL_ENV))
    assert cfg.recording_enabled is False
    assert cfg.recording_egress_url == ""


@pytest.mark.asyncio
async def test_recording_hook_inert_when_disabled():
    """The hook must do nothing (no API call, no exception) when disabled."""
    cfg = load_config(dict(FULL_ENV))
    assert await _start_call_recording(cfg, "room-9") is None


def test_recording_enabled_requires_egress_url():
    env = dict(FULL_ENV, VOICE_RECORDING_ENABLED="true")
    with pytest.raises(ConfigError) as exc_info:
        load_config(env)
    assert "VOICE_RECORDING_EGRESS_URL" in str(exc_info.value)


def test_recording_enabled_rejects_non_s3_url():
    env = dict(FULL_ENV, VOICE_RECORDING_ENABLED="true",
               VOICE_RECORDING_EGRESS_URL="https://example.com/rec")
    with pytest.raises(ConfigError) as exc_info:
        load_config(env)
    assert "VOICE_RECORDING_EGRESS_URL" in str(exc_info.value)


def test_recording_enabled_requires_storage_creds():
    env = dict(FULL_ENV, VOICE_RECORDING_ENABLED="true",
               VOICE_RECORDING_EGRESS_URL="s3://my-bucket/voxticket")
    with pytest.raises(ConfigError) as exc_info:
        load_config(env)
    assert "VOICE_RECORDING_S3_REGION" in str(exc_info.value)


def test_recording_full_config_loads():
    env = dict(FULL_ENV, VOICE_RECORDING_ENABLED="yes",
               VOICE_RECORDING_EGRESS_URL="s3://my-bucket/voxticket",
               VOICE_RECORDING_S3_REGION="eu-west-1",
               VOICE_RECORDING_S3_ACCESS_KEY="ak",
               VOICE_RECORDING_S3_SECRET_KEY="sk")
    cfg = load_config(env)
    assert cfg.recording_enabled is True
    assert cfg.recording_egress_url == "s3://my-bucket/voxticket"


def test_garbage_recording_bool_raises_config_error():
    env = dict(FULL_ENV, VOICE_RECORDING_ENABLED="maybe")
    with pytest.raises(ConfigError) as exc_info:
        load_config(env)
    assert "VOICE_RECORDING_ENABLED" in str(exc_info.value)
