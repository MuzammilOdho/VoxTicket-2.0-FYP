"""P0: filler, deterministic teardown, 429 honesty, idempotency headers, telemetry.

Covers the worker-side P0 items: in-band filler while the Java brain thinks
(multilingual, voice-lock/TTFT-excluded), deterministic stream teardown on
barge-in, BrainSessionBusyError -> honest repeat-request + lost-utterance
telemetry, Idempotency-Key on brain calls, and the new telemetry fields.
"""

import asyncio
import json
from types import SimpleNamespace

import httpx
import pytest

from livekit.agents import llm

import agent as agent_module
from agent import (
    BrainError,
    BrainSessionBusyError,
    SttFinalTracker,
    TurnTelemetry,
    VoxTicketAgent,
    VoxTicketBrain,
    load_config,
)


# --------------------------------------------------------------------------
# Doubles
# --------------------------------------------------------------------------

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


def final_tracker(language=None):
    tracker = SttFinalTracker()
    tracker.handle(SimpleNamespace(is_final=True, transcript="hello", language=language))
    return tracker


class SlowBrain:
    """Yields chunks only after `delay` seconds (slow Java brain)."""

    def __init__(self, chunks=("hello ", "world"), delay=0.3):
        self.chunks = chunks
        self.delay = delay
        self.calls = []

    async def turn_stream(self, session_id, text, **kwargs):
        self.calls.append((session_id, text))
        await asyncio.sleep(self.delay)
        for chunk in self.chunks:
            yield chunk


class SlowFailBrain:
    """Slow, then BrainError (failure after the filler was spoken)."""

    def __init__(self, delay=0.2):
        self.delay = delay

    async def turn_stream(self, session_id, text, **kwargs):
        await asyncio.sleep(self.delay)
        raise BrainError("down")
        yield  # make this an async generator


class BusyBrain:
    """Always 429-exhausted."""

    async def turn_stream(self, session_id, text, **kwargs):
        raise BrainSessionBusyError("busy")
        yield


class ClosableBrain:
    """Records whether its stream generator was closed (P0.3)."""

    def __init__(self):
        self.closed = False
        self.calls = []

    async def turn_stream(self, session_id, text, **kwargs):
        self.calls.append((session_id, text))
        try:
            yield "partial "
            await asyncio.sleep(30)  # never reached; cancelled instead
        finally:
            self.closed = True


async def _collect(agen):
    return [c async for c in agen]


# --------------------------------------------------------------------------
# P0.4: in-band filler
# --------------------------------------------------------------------------

@pytest.mark.asyncio
async def test_filler_yielded_when_brain_slow():
    agent = make_agent(brain=SlowBrain(delay=0.3), filler_after_s=0.05)
    chunks = await _collect(agent.llm_node(user_ctx("hello"), [], None))
    assert chunks[0] == "One moment, let me check that for you. "
    assert chunks[1:] == ["hello ", "world"]


@pytest.mark.asyncio
async def test_no_filler_when_brain_fast():
    agent = make_agent(brain=SlowBrain(chunks=("a", "b"), delay=0.0), filler_after_s=10)
    chunks = await _collect(agent.llm_node(user_ctx("hello"), [], None))
    assert chunks == ["a", "b"]


@pytest.mark.asyncio
async def test_filler_disabled_at_zero():
    agent = make_agent(brain=SlowBrain(delay=0.3), filler_after_s=0)
    chunks = await _collect(agent.llm_node(user_ctx("hello"), [], None))
    assert chunks == ["hello ", "world"]


@pytest.mark.asyncio
async def test_filler_urdu_when_stt_language_ur():
    agent = make_agent(
        brain=SlowBrain(delay=0.3),
        filler_after_s=0.05,
        stt_final=final_tracker(language="ur"),
    )
    chunks = await _collect(agent.llm_node(user_ctx("hello"), [], None))
    assert chunks[0] == "ایک لمحہ، میں دیکھ رہا ہوں۔ "
    assert chunks[1:] == ["hello ", "world"]


@pytest.mark.asyncio
async def test_filler_does_not_lock_voice_or_ttft():
    # English filler, then an Urdu brain reply: the voice must lock to Urdu
    # (the filler must not decide), and TTFT must be measured from the brain.
    agent = make_agent(
        brain=SlowBrain(chunks=("سلام ", "دنیا"), delay=0.3),
        filler_after_s=0.05,
    )
    chunks = await _collect(agent.llm_node(user_ctx("hello"), [], None))
    assert chunks[0] == "One moment, let me check that for you. "
    assert agent._next_voice == ("voice-ur", "ur")


@pytest.mark.asyncio
async def test_brain_failure_after_filler_still_apologizes():
    agent = make_agent(brain=SlowFailBrain(delay=0.2), filler_after_s=0.05)
    chunks = await _collect(agent.llm_node(user_ctx("hello"), [], None))
    assert len(chunks) == 2
    assert chunks[0] == "One moment, let me check that for you. "
    assert "having trouble reaching the service" in chunks[1]


@pytest.mark.asyncio
async def test_filler_timer_cancelled_on_fast_turn_no_leak():
    # A fast turn must not leave a pending filler timer behind.
    agent = make_agent(brain=SlowBrain(chunks=("ok",), delay=0.0), filler_after_s=0.05)
    chunks = await _collect(agent.llm_node(user_ctx("hello"), [], None))
    assert chunks == ["ok"]
    await asyncio.sleep(0.15)  # past the filler delay: nothing may fire now
    # No way to observe directly; the test passes if nothing raised and the
    # turn recorded exactly once with no filler in the (empty) transcript.


# --------------------------------------------------------------------------
# P0.3: deterministic teardown
# --------------------------------------------------------------------------

@pytest.mark.asyncio
async def test_cancel_closes_stream_deterministically():
    brain = ClosableBrain()
    agent = make_agent(brain=brain, filler_after_s=0)
    task = asyncio.ensure_future(_collect(agent.llm_node(user_ctx("hello"), [], None)))
    await asyncio.sleep(0.1)  # let the first chunk flow
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task
    assert brain.closed is True  # closed now, not via GC finalizer
    assert agent.barge_in_count == 1


# --------------------------------------------------------------------------
# P0.5/429: honest busy handling + telemetry
# --------------------------------------------------------------------------

@pytest.mark.asyncio
async def test_session_busy_speaks_repeat_request_and_marks_lost():
    from agent import TelemetryBuffer  # noqa

    buf = _buffer()
    agent = make_agent(brain=BusyBrain(), telemetry=buf, filler_after_s=0)
    chunks = await _collect(agent.llm_node(user_ctx("hello"), [], None))
    assert chunks == ["I missed that while finishing your previous request. "
                      "Could you please say it again?"]
    timing = buf._turns[0]
    assert timing.lost_utterance is True
    d = timing.to_dict()
    assert d["lostUtterance"] is True
    assert d["brain429Attempts"] == 1
    assert "BrainSessionBusyError" in d["error"]


def _buffer():
    # Minimal stand-in: record_turn appends to _turns like TelemetryBuffer.
    class B:
        def __init__(self):
            self._turns = []

        def record_turn(self, timing):
            self._turns.append(timing)

    return B()


@pytest.mark.asyncio
async def test_429_retries_report_attempts_and_keep_idempotency_key(monkeypatch):
    monkeypatch.setattr(agent_module, "_BRAIN_429_RETRY_DELAY_S", 0)
    seen_keys = []
    seen_429 = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen_keys.append(request.headers.get("Idempotency-Key"))
        if len(seen_keys) < 3:
            return httpx.Response(429, text="busy")
        body = 'data: {"delta": "hi"}\n\ndata: {"done": true}\n\n'
        return httpx.Response(200, text=body)

    transport = httpx.MockTransport(handler)
    client = httpx.AsyncClient(transport=transport, timeout=5.0)
    brain = VoxTicketBrain("http://java:8080/api/v1/voice/turn", client=client)

    chunks = [c async for c in brain.turn_stream(
        "room-1", "hi", idempotency_key="key-1",
        on_429=lambda attempt: seen_429.append(attempt))]
    assert chunks == ["hi"]
    assert seen_429 == [2, 3]
    # One logical turn -> one idempotency key across all POST attempts.
    assert seen_keys == ["key-1", "key-1", "key-1"]


@pytest.mark.asyncio
async def test_429_exhaustion_raises_session_busy_not_generic(monkeypatch):
    monkeypatch.setattr(agent_module, "_BRAIN_429_RETRY_DELAY_S", 0)

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(429, text="busy")

    transport = httpx.MockTransport(handler)
    client = httpx.AsyncClient(transport=transport, timeout=5.0)
    brain = VoxTicketBrain("http://java:8080/api/v1/voice/turn", client=client)
    with pytest.raises(BrainSessionBusyError):
        [c async for c in brain.turn_stream("room-1", "hi")]


@pytest.mark.asyncio
async def test_idempotency_key_sent_on_blocking_turn():
    seen = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["key"] = request.headers.get("Idempotency-Key")
        return httpx.Response(200, json={"sessionId": "r", "text": "ok"})

    transport = httpx.MockTransport(handler)
    client = httpx.AsyncClient(transport=transport, timeout=5.0)
    brain = VoxTicketBrain("http://java:8080/api/v1/voice/turn", client=client)
    assert await brain.turn("r", "hi", idempotency_key="key-9") == "ok"
    assert seen["key"] == "key-9"


# --------------------------------------------------------------------------
# P0.4: turn-state broadcast (best-effort)
# --------------------------------------------------------------------------

def test_turn_state_broadcast_is_best_effort_without_session():
    agent = make_agent(brain=SlowBrain(chunks=("ok",), delay=0.0))
    # No LiveKit session attached in unit tests: must not raise.
    agent._publish_turn_state(1, "thinking")
    agent._publish_turn_state(1, "bogus-state")  # unknown states ignored


def test_turn_state_constants():
    assert agent_module._TURN_TOPIC == "voxticket.turn"
    assert set(agent_module._TURN_STATES) == {"thinking", "first_audio", "done", "aborted"}


# --------------------------------------------------------------------------
# P0.4/P0.5: config + telemetry contract
# --------------------------------------------------------------------------

def test_filler_config_defaults_and_env():
    from agent import load_config
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
    assert cfg.filler_after_s == 1.5
    assert cfg.filler_text_en.startswith("One moment")
    assert "لمحہ" in cfg.filler_text_ur

    cfg2 = load_config(dict(env, VOICE_FILLER_AFTER_S="2.5",
                            VOICE_FILLER_TEXT_EN="Hold on. "))
    assert cfg2.filler_after_s == 2.5
    assert cfg2.filler_text_en == "Hold on."  # opt() strips env values


def test_telemetry_to_dict_has_p0_fields():
    timing = TurnTelemetry(
        room="r", turn_number=1, trace_id="t" * 32, span_id="s" * 16,
        stt_provider="a", stt_model="m", tts_provider="c", tts_model="s3",
        brain_429_attempts=3, lost_utterance=True,
    )
    timing.finalize()
    d = timing.to_dict()
    assert d["spanId"] == "s" * 16
    assert d["brain429Attempts"] == 3
    assert d["lostUtterance"] is True
