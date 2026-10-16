"""P1.4: mid-stream stall watchdog.

When brain deltas flow and then stop mid-sentence, the caller must not sit in
post-speech silence: the watchdog yields an honest repair phrase, closes the
stream deterministically, and marks the turn stalled in telemetry.
"""

import asyncio

import pytest

from livekit.agents import llm

from agent import TurnTelemetry, VoxTicketAgent, load_config


class FakeVoices:
    def set_voice(self, voice_id, language):
        pass


def make_agent(brain=None, telemetry=None, **overrides):
    kwargs = dict(
        brain=brain,
        session_id="room-9",
        tts=object(),
        voices=FakeVoices(),
        voice_en="voice-en",
        voice_ur="voice-ur",
        greeting="hi",
        trace_id="t" * 32,
        telemetry=telemetry,
        stt_provider="assemblyai",
        stt_model="universal-3-6-pro",
        tts_provider="cartesia",
        tts_model="sonic-3.6",
    )
    kwargs.update(overrides)
    return VoxTicketAgent(**kwargs)


def user_ctx(text):
    ctx = llm.ChatContext()
    ctx.add_message(role="user", content=text)
    return ctx


class Buf:
    def __init__(self):
        self._turns = []

    def record_turn(self, timing):
        self._turns.append(timing)


class StallingBrain:
    """Yields `chunks`, then hangs until cancelled/closed."""

    def __init__(self, chunks=("a", "b")):
        self.chunks = chunks
        self.closed = False

    async def turn_stream(self, session_id, text, **kwargs):
        try:
            for chunk in self.chunks:
                yield chunk
            await asyncio.sleep(30)  # hang; the watchdog must end this
        finally:
            self.closed = True


class SteadyBrain:
    """Slow-but-steady: must never trip the watchdog."""

    async def turn_stream(self, session_id, text, **kwargs):
        for i in range(5):
            await asyncio.sleep(0.05)
            yield f"d{i} "


class QuickBrain:
    async def turn_stream(self, session_id, text, **kwargs):
        yield "ok"


async def _collect(agen):
    return [c async for c in agen]


REPAIR = "Sorry, I lost my train of thought — could you say that again?"


@pytest.mark.asyncio
async def test_stall_yields_repair_and_marks_stalled():
    brain = StallingBrain()
    buf = Buf()
    agent = make_agent(brain=brain, telemetry=buf, filler_after_s=0, stall_after_s=0.15)
    chunks = await _collect(agent.llm_node(user_ctx("hello"), [], None))
    assert chunks == ["a", "b", REPAIR]
    timing = buf._turns[0]
    assert timing.stalled is True
    assert timing.aborted is True
    assert timing.to_dict()["stalled"] is True
    assert brain.closed is True  # closed deterministically, not via GC


@pytest.mark.asyncio
async def test_steady_slow_stream_never_stalls():
    buf = Buf()
    agent = make_agent(brain=SteadyBrain(), telemetry=buf,
                       filler_after_s=0, stall_after_s=0.15)
    chunks = await _collect(agent.llm_node(user_ctx("hello"), [], None))
    assert chunks == ["d0 ", "d1 ", "d2 ", "d3 ", "d4 "]
    assert buf._turns[0].stalled is False
    assert buf._turns[0].aborted is False


@pytest.mark.asyncio
async def test_stall_disabled_at_zero():
    agent = make_agent(brain=QuickBrain(), filler_after_s=0, stall_after_s=0)
    chunks = await _collect(agent.llm_node(user_ctx("hello"), [], None))
    assert chunks == ["ok"]


def test_stall_config_defaults_and_env():
    env = {
        "LIVEKIT_URL": "ws://localhost:7880",
        "LIVEKIT_API_KEY": "devkey",
        "LIVEKIT_API_SECRET": "secret",
        "ASSEMBLYAI_API_KEY": "aai-key",
        "CARTESIA_API_KEY": "cart-key",
        "CARTESIA_VOICE_EN": "voice-en-id",
        "CARTESIA_VOICE_UR": "voice-ur-id",
    }
    cfg = load_config(dict(env))
    assert cfg.stall_after_s == 8.0
    assert "train of thought" in cfg.stall_repair
    cfg2 = load_config(dict(env, VOICE_STALL_AFTER_S="5"))
    assert cfg2.stall_after_s == 5.0


def test_telemetry_to_dict_has_stalled():
    timing = TurnTelemetry(
        room="r", turn_number=1, trace_id="t" * 32, span_id="s" * 16,
        stt_provider="a", stt_model="m", tts_provider="c", tts_model="s3",
        stalled=True,
    )
    timing.finalize()
    assert timing.to_dict()["stalled"] is True
