"""Adaptive audio: network-tier broadcast decoding and worker-side patience."""

import json

import pytest

from livekit.agents import llm  # noqa: F401  (kept for parity with other test modules)

from agent import (
    _NETWORK_BROADCAST_VERSION,
    _NETWORK_ENDPOINTING,
    _NETWORK_TIERS,
    VoxTicketAgent,
    decode_tier_broadcast,
)


# --------------------------------------------------------------------------
# decode_tier_broadcast
# --------------------------------------------------------------------------

def _broadcast_bytes(payload: dict) -> bytes:
    return json.dumps(payload).encode("utf-8")


def test_decode_valid_broadcast():
    payload = {
        "v": _NETWORK_BROADCAST_VERSION,
        "tier": "low",
        "lossPct": 2.5,
        "jitterMs": 12.0,
        "rttMs": 180.0,
        "at": "2026-10-09T19:00:00+00:00",
    }
    decoded = decode_tier_broadcast(_broadcast_bytes(payload))
    assert decoded is not None
    assert decoded["tier"] == "low"
    assert decoded["lossPct"] == 2.5


def test_decode_rejects_malformed_json():
    assert decode_tier_broadcast(b"not-json{") is None
    assert decode_tier_broadcast(b"") is None


def test_decode_rejects_non_dict():
    assert decode_tier_broadcast(b"[1, 2, 3]") is None
    assert decode_tier_broadcast(b'"low"') is None


def test_decode_rejects_wrong_version():
    payload = {"v": _NETWORK_BROADCAST_VERSION + 1, "tier": "low"}
    assert decode_tier_broadcast(_broadcast_bytes(payload)) is None


def test_decode_rejects_unknown_tier():
    payload = {"v": _NETWORK_BROADCAST_VERSION, "tier": "ultra"}
    assert decode_tier_broadcast(_broadcast_bytes(payload)) is None


def test_decode_rejects_missing_tier():
    payload = {"v": _NETWORK_BROADCAST_VERSION}
    assert decode_tier_broadcast(_broadcast_bytes(payload)) is None


# --------------------------------------------------------------------------
# apply_network_tier
# --------------------------------------------------------------------------

class FakeBrain:
    async def turn(self, session_id: str, text: str, **kwargs) -> str:
        return "ok"

    async def turn_stream(self, session_id: str, text: str, **kwargs):
        yield "ok"


class FakeTTS:
    def update_options(self, **kwargs):
        pass


class FakeVoices:
    def set_voice(self, voice_id: str, language: str) -> None:
        pass


class FakeSession:
    """Test double for AgentSession.update_options: records endpointing opts."""

    def __init__(self, fail: bool = False):
        self.fail = fail
        self.endpointing_calls: list[dict] = []

    def update_options(self, *, endpointing_opts=None, **kwargs):
        if self.fail:
            raise RuntimeError("boom")
        self.endpointing_calls.append(dict(endpointing_opts or {}))


def make_agent(monkeypatch, fail_session: bool = False) -> tuple[VoxTicketAgent, FakeSession]:
    agent = VoxTicketAgent(
        brain=FakeBrain(),
        session_id="room-9",
        tts=FakeTTS(),
        voices=FakeVoices(),
        voice_en="voice-en",
        voice_ur="voice-ur",
        greeting="hi",
    )
    fake_session = FakeSession(fail=fail_session)
    monkeypatch.setattr(VoxTicketAgent, "session", property(lambda self: fake_session))
    return agent, fake_session


@pytest.mark.parametrize("tier", list(_NETWORK_TIERS))
def test_apply_tier_sets_endpointing_patience(monkeypatch, tier):
    agent, fake_session = make_agent(monkeypatch)
    assert agent.network_tier is None
    assert agent.apply_network_tier(tier) is True
    assert agent.network_tier == tier
    min_delay, max_delay = _NETWORK_ENDPOINTING[tier]
    assert fake_session.endpointing_calls == [
        {"min_delay": min_delay, "max_delay": max_delay}
    ]


def test_apply_tier_ignores_repeats(monkeypatch):
    agent, fake_session = make_agent(monkeypatch)
    assert agent.apply_network_tier("low") is True
    assert agent.apply_network_tier("low") is False
    assert len(fake_session.endpointing_calls) == 1


@pytest.mark.parametrize("tier", [None, "", "ultra", "FULL", 123])
def test_apply_tier_rejects_unknown(monkeypatch, tier):
    agent, fake_session = make_agent(monkeypatch)
    assert agent.apply_network_tier(tier) is False
    assert agent.network_tier is None
    assert fake_session.endpointing_calls == []


def test_apply_tier_fail_open_when_session_update_raises(monkeypatch):
    agent, fake_session = make_agent(monkeypatch, fail_session=True)
    assert agent.apply_network_tier("survival") is False
    assert agent.network_tier is None  # unchanged: keep current patience


def test_tier_patience_ordering():
    """Worse tiers wait longer: full < low < survival on both delays."""
    full = _NETWORK_ENDPOINTING["full"]
    low = _NETWORK_ENDPOINTING["low"]
    survival = _NETWORK_ENDPOINTING["survival"]
    assert full < low < survival  # tuple comparison, element-wise
