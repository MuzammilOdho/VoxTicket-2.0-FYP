"""P3 voice telemetry: tap points, correlation headers, buffer, contracts.

Covers: TelemetryBuffer record/flush/heartbeat JSON contracts, drop-on-failure
(without raising), throttled warnings, boundedness, W3C traceparent headers on
both brain POSTs (incl. the 404->blocking fallback), llm_node cancellation ->
barge_in, tts_node first-audio stamping, SttFinalTracker, and close-event
classification.
"""

import asyncio
import json
import logging
import re
import time
from types import SimpleNamespace

import httpx
import pytest

from livekit.agents import llm

from agent import (
    SttFinalTracker,
    TelemetryBuffer,
    TurnTelemetry,
    VoxTicketAgent,
    VoxTicketBrain,
    _build_traceparent,
    _classify_close,
)

TRACEPARENT_RE = re.compile(r"^00-[0-9a-f]{32}-[0-9a-f]{16}-01$")


def make_buffer(**overrides):
    kwargs = dict(
        worker_id="worker-1",
        telemetry_url="http://java:8080/api/v1/voice/telemetry",
        heartbeat_url="http://java:8080/api/v1/voice/worker-heartbeat",
        secret="s3cret",
        flush_seconds=60.0,
        heartbeat_seconds=3600.0,
        stt_provider="assemblyai",
        tts_provider="cartesia",
    )
    kwargs.update(overrides)
    return TelemetryBuffer(**kwargs)


def make_timing(**overrides):
    kwargs = dict(
        room="room-9", turn_number=1, trace_id="t" * 32, span_id="s" * 16,
        stt_provider="assemblyai", stt_model="universal-3-6-pro",
        tts_provider="cartesia", tts_model="sonic-3.6",
    )
    kwargs.update(overrides)
    return TurnTelemetry(**kwargs)


class FakeBrain:
    """Test double mirroring VoxTicketBrain's interface (incl. traceparent)."""

    def __init__(self, chunks=("ok",), error=None):
        self.chunks = chunks
        self.error = error
        self.calls = []
        self.traceparents = []

    async def turn(self, session_id, text, **kwargs):
        self.calls.append((session_id, text))
        self.traceparents.append(kwargs.get("traceparent"))
        if self.error is not None:
            raise self.error
        return "blocking-reply"

    async def turn_stream(self, session_id, text, **kwargs):
        self.calls.append((session_id, text))
        self.traceparents.append(kwargs.get("traceparent"))
        if self.error is not None:
            raise self.error
        for chunk in self.chunks:
            yield chunk


class FakeVoices:
    def set_voice(self, voice_id, language):
        pass


def make_agent(brain=None, telemetry=None, **overrides):
    kwargs = dict(
        brain=brain or FakeBrain(),
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


# --------------------------------------------------------------------------
# Buffer: exact JSON contracts
# --------------------------------------------------------------------------

@pytest.mark.asyncio
async def test_flush_posts_exact_json_contract():
    posted = {}

    def handler(request: httpx.Request) -> httpx.Response:
        posted["url"] = str(request.url)
        posted["secret"] = request.headers.get("X-VoxTicket-Telemetry-Secret")
        posted["json"] = json.loads(request.content)
        return httpx.Response(202)

    buf = make_buffer(client=httpx.AsyncClient(transport=httpx.MockTransport(handler)))
    timing = make_timing(stt_language="ur")
    timing.t_stt_event = 100.0
    timing.t_stt_final = 100.5
    timing.t_brain_start = 100.6
    timing.t_brain_first_delta = 101.6
    timing.t_tts_first_audio = 102.1
    timing.t_turn_end = 105.5
    timing.finalize()
    buf.record_turn(timing)
    buf.record_call(room="room-9", trace_id="t" * 32, outcome="COMPLETED",
                    barge_in_count=2, disconnect_reason="user_initiated",
                    started_at="2026-10-06T18:00:00+00:00",
                    ended_at="2026-10-06T18:05:00+00:00")

    await buf._flush_once()

    assert posted["url"] == "http://java:8080/api/v1/voice/telemetry"
    assert posted["secret"] == "s3cret"
    body = posted["json"]
    assert body["workerId"] == "worker-1"
    assert body["turns"] == [{
        "room": "room-9",
        "turnNumber": 1,
        "traceId": "t" * 32,
        "sttLatencyMs": pytest.approx(500.0),
        "brainTtftMs": pytest.approx(1000.0),
        "ttsFirstAudioMs": pytest.approx(500.0),
        "e2eMs": pytest.approx(5500.0),
        "aborted": False,
        "bargeIn": False,
        "sttLanguage": "ur",
        "sttProvider": "assemblyai",
        "sttModel": "universal-3-6-pro",
        "ttsProvider": "cartesia",
        "ttsModel": "sonic-3.6",
        "error": None,
    }]
    assert body["calls"] == [{
        "room": "room-9",
        "traceId": "t" * 32,
        "outcome": "COMPLETED",
        "bargeInCount": 2,
        "disconnectReason": "user_initiated",
        "startedAt": "2026-10-06T18:00:00+00:00",
        "endedAt": "2026-10-06T18:05:00+00:00",
    }]
    # flushed records are gone from the buffer
    assert buf.pending_turns == 0


@pytest.mark.asyncio
async def test_flush_posts_nothing_when_empty():
    calls = []

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(request)
        return httpx.Response(202)

    buf = make_buffer(client=httpx.AsyncClient(transport=httpx.MockTransport(handler)))
    await buf._flush_once()
    assert calls == []


@pytest.mark.asyncio
async def test_failed_flush_drops_batch_without_raising_and_warns_once(caplog):
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("java is down")

    buf = make_buffer(client=httpx.AsyncClient(transport=httpx.MockTransport(handler)))
    t1 = make_timing()
    t1.finalize()
    buf.record_turn(t1)

    with caplog.at_level(logging.WARNING, logger="voxticket-voice"):
        await buf._flush_once()  # must not raise
    assert buf.pending_turns == 0  # dropped, not requeued
    assert caplog.text.count("telemetry flush failed") == 1

    # a second failure within the throttle window stays silent
    t2 = make_timing(turn_number=2)
    t2.finalize()
    buf.record_turn(t2)
    with caplog.at_level(logging.WARNING, logger="voxticket-voice"):
        await buf._flush_once()
    assert caplog.text.count("telemetry flush failed") == 1


@pytest.mark.asyncio
async def test_rejected_flush_drops_batch_with_throttled_warning(caplog):
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(500, text="boom")

    buf = make_buffer(client=httpx.AsyncClient(transport=httpx.MockTransport(handler)))
    t = make_timing()
    t.finalize()
    buf.record_turn(t)
    with caplog.at_level(logging.WARNING, logger="voxticket-voice"):
        await buf._flush_once()
    assert buf.pending_turns == 0
    assert "telemetry flush rejected (HTTP 500)" in caplog.text


@pytest.mark.asyncio
async def test_unfinalized_turns_are_held_for_next_flush():
    posted = []

    def handler(request: httpx.Request) -> httpx.Response:
        posted.append(json.loads(request.content))
        return httpx.Response(202)

    buf = make_buffer(client=httpx.AsyncClient(transport=httpx.MockTransport(handler)))
    t = make_timing()  # not finalized, t_turn_end is "now"
    t.t_turn_end = time.perf_counter()
    buf.record_turn(t)
    await buf._flush_once()
    assert posted == []  # held back
    assert buf.pending_turns == 1
    t.finalize()
    await buf._flush_once()
    assert len(posted) == 1


@pytest.mark.asyncio
async def test_heartbeat_posts_worker_contract():
    posted = {}

    def handler(request: httpx.Request) -> httpx.Response:
        posted["url"] = str(request.url)
        posted["secret"] = request.headers.get("X-VoxTicket-Telemetry-Secret")
        posted["json"] = json.loads(request.content)
        return httpx.Response(200)

    buf = make_buffer(client=httpx.AsyncClient(transport=httpx.MockTransport(handler)))
    await buf._heartbeat_once()
    assert posted["url"] == "http://java:8080/api/v1/voice/worker-heartbeat"
    assert posted["secret"] == "s3cret"
    body = posted["json"]
    assert body["workerId"] == "worker-1"
    assert body["sttProvider"] == "assemblyai"
    assert body["ttsProvider"] == "cartesia"
    assert isinstance(body["activeRooms"], int)


def test_buffer_drops_oldest_past_bound():
    buf = make_buffer()
    for i in range(1005):
        t = make_timing(turn_number=i)
        t.finalize()
        buf.record_turn(t)
    assert buf.pending_turns == 1000
    assert buf.dropped_turns == 5
    # oldest dropped, newest kept, order preserved
    assert buf._turns[0].turn_number == 5
    assert buf._turns[-1].turn_number == 1004


# --------------------------------------------------------------------------
# Correlation: traceparent headers
# --------------------------------------------------------------------------

def test_build_traceparent_format_and_rejection():
    good = _build_traceparent("a" * 32, "b" * 16)
    assert good == "00-" + "a" * 32 + "-" + "b" * 16 + "-01"
    assert TRACEPARENT_RE.match(good)
    assert _build_traceparent(None, "b" * 16) is None
    assert _build_traceparent("", "b" * 16) is None
    assert _build_traceparent("xyz", "b" * 16) is None
    assert _build_traceparent("a" * 32, "short") is None
    assert _build_traceparent("A" * 32, "b" * 16) is None  # lowercase hex only
    assert _build_traceparent("a" * 32, None) is None


@pytest.mark.asyncio
async def test_brain_turn_sends_valid_traceparent():
    seen = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["traceparent"] = request.headers.get("traceparent")
        return httpx.Response(200, json={"text": "hi"})

    brain = VoxTicketBrain(
        "http://java:8080/api/v1/voice/turn",
        client=httpx.AsyncClient(transport=httpx.MockTransport(handler)))
    tp = "00-" + "a" * 32 + "-" + "b" * 16 + "-01"
    assert await brain.turn("r", "hi", traceparent=tp) == "hi"
    assert TRACEPARENT_RE.match(seen["traceparent"])
    assert seen["traceparent"] == tp


@pytest.mark.asyncio
async def test_brain_stream_propagates_traceparent_on_fallback():
    seen = {}

    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path.endswith("/turn/stream"):
            seen["stream_tp"] = request.headers.get("traceparent")
            return httpx.Response(404, text="not found")
        seen["blocking_tp"] = request.headers.get("traceparent")
        return httpx.Response(200, json={"text": "fallback reply"})

    brain = VoxTicketBrain(
        "http://java:8080/api/v1/voice/turn",
        client=httpx.AsyncClient(transport=httpx.MockTransport(handler)))
    tp = "00-" + "c" * 32 + "-" + "d" * 16 + "-01"
    chunks = [c async for c in brain.turn_stream("r", "hi", traceparent=tp)]
    assert chunks == ["fallback reply"]
    assert TRACEPARENT_RE.match(seen["stream_tp"])
    assert seen["stream_tp"] == seen["blocking_tp"] == tp


@pytest.mark.asyncio
async def test_brain_calls_still_work_without_traceparent():
    # backward compatibility: existing callers pass no correlation data
    def handler(request: httpx.Request) -> httpx.Response:
        assert "traceparent" not in request.headers
        return httpx.Response(200, json={"text": "hi"})

    brain = VoxTicketBrain(
        "http://java:8080/api/v1/voice/turn",
        client=httpx.AsyncClient(transport=httpx.MockTransport(handler)))
    assert await brain.turn("r", "hi") == "hi"


# --------------------------------------------------------------------------
# Tap points: llm_node / tts_node
# --------------------------------------------------------------------------

@pytest.mark.asyncio
async def test_llm_node_records_turn_with_traceparent_and_sequence():
    buf = make_buffer()
    brain = FakeBrain(chunks=["hello ", "there"])
    agent = make_agent(brain=brain, telemetry=buf, trace_id="a" * 32)

    await _drain(agent.llm_node(user_ctx("hi"), [], None))
    await _drain(agent.llm_node(user_ctx("again"), [], None))

    assert buf.pending_turns == 2
    t1, t2 = buf._turns
    assert (t1.turn_number, t2.turn_number) == (1, 2)
    assert t1.trace_id == "a" * 32
    assert re.fullmatch(r"[0-9a-f]{16}", t1.span_id)
    assert t1.span_id != t2.span_id  # one span per turn
    # the traceparent the agent built reached the brain on both turns
    assert brain.traceparents == [
        f"00-{'a' * 32}-{t1.span_id}-01",
        f"00-{'a' * 32}-{t2.span_id}-01",
    ]
    assert t1.to_dict()["brainTtftMs"] is not None
    assert t1.to_dict()["e2eMs"] is not None
    assert t1.to_dict()["sttLatencyMs"] is None  # no STT event observed
    assert t1.to_dict()["aborted"] is False


@pytest.mark.asyncio
async def test_llm_node_uses_stt_final_event_for_latency_and_language():
    buf = make_buffer()
    tracker = SttFinalTracker()
    agent = make_agent(brain=FakeBrain(chunks=["ok"]), telemetry=buf, stt_final=tracker)

    # simulate the session's user_input_transcribed final event arriving first
    ev = SimpleNamespace(is_final=True, transcript="hello", language="ur")
    tracker.handle(ev)

    await _drain(agent.llm_node(user_ctx("hello"), [], None))
    d = buf._turns[0].to_dict()
    assert d["sttLanguage"] == "ur"
    assert d["sttLatencyMs"] is not None
    assert d["sttLatencyMs"] >= 0
    # tracker slot is consumed: a second turn without a new event gets nulls
    await _drain(agent.llm_node(user_ctx("hello"), [], None))
    d2 = buf._turns[1].to_dict()
    assert d2["sttLatencyMs"] is None
    assert d2["sttLanguage"] is None


@pytest.mark.asyncio
async def test_llm_node_cancellation_marks_barge_in():
    class CancellingBrain(FakeBrain):
        async def turn_stream(self, session_id, text, **kwargs):
            self.calls.append((session_id, text))
            yield "partial "
            raise asyncio.CancelledError()

    buf = make_buffer()
    agent = make_agent(brain=CancellingBrain(), telemetry=buf)

    with pytest.raises(asyncio.CancelledError):
        [c async for c in agent.llm_node(user_ctx("hello"), [], None)]

    assert agent.barge_in_count == 1
    assert buf.pending_turns == 1
    timing = buf._turns[0]
    assert timing.barge_in is True
    assert timing.aborted is True
    d = timing.to_dict()
    assert d["bargeIn"] is True
    assert d["aborted"] is True
    # first delta arrived before the cancel: TTFT is measurable
    assert d["brainTtftMs"] is not None


@pytest.mark.asyncio
async def test_llm_node_brain_error_marks_aborted_with_error():
    from agent import BrainError

    buf = make_buffer()
    agent = make_agent(brain=FakeBrain(error=BrainError("down")), telemetry=buf)
    chunks = [c async for c in agent.llm_node(user_ctx("hello"), [], None)]
    assert len(chunks) == 1  # apology still spoken
    d = buf._turns[0].to_dict()
    assert d["aborted"] is True
    assert d["error"] is not None and "BrainError" in d["error"]
    assert d["brainTtftMs"] is None  # no delta ever arrived


@pytest.mark.asyncio
async def test_tts_node_stamps_first_audio_and_finalizes(monkeypatch):
    from livekit.agents.voice.agent import Agent as VoiceAgent

    async def ok_tts_node(self, text, model_settings):
        yield "frame1"
        yield "frame2"

    monkeypatch.setattr(VoiceAgent.default, "tts_node", ok_tts_node)

    buf = make_buffer()
    agent = make_agent(telemetry=buf)
    timing = make_timing()
    timing.t_brain_first_delta = time.perf_counter()
    agent._pending_turn = timing
    agent._next_voice = ("voice-en", "en")

    frames = [f async for f in agent.tts_node("hello", None)]
    assert frames == ["frame1", "frame2"]
    assert timing.t_tts_first_audio is not None
    assert timing.finalized is True
    d = timing.to_dict()
    assert d["ttsFirstAudioMs"] is not None
    assert d["ttsFirstAudioMs"] >= 0


# --------------------------------------------------------------------------
# SttFinalTracker
# --------------------------------------------------------------------------

def test_stt_tracker_ignores_interim_and_empty():
    tr = SttFinalTracker()
    tr.handle(SimpleNamespace(is_final=False, transcript="hel", language="en"))
    tr.handle(SimpleNamespace(is_final=True, transcript="   ", language="en"))
    assert tr.take() == (None, None)


def test_stt_tracker_takes_latest_final_and_clears():
    tr = SttFinalTracker()
    tr.handle(SimpleNamespace(is_final=True, transcript="hello", language="ur"))
    ts, lang = tr.take()
    assert ts is not None and lang == "ur"
    assert tr.take() == (None, None)  # cleared: no stale reuse


def test_stt_tracker_never_raises_on_weird_events():
    tr = SttFinalTracker()
    tr.handle(None)
    tr.handle(SimpleNamespace())
    tr.handle("not-an-event")
    assert tr.take() == (None, None)


# --------------------------------------------------------------------------
# Close-event classification
# --------------------------------------------------------------------------

def test_classify_close_maps_reasons():
    def ev(reason, error=None):
        return SimpleNamespace(reason=SimpleNamespace(value=reason), error=error)

    assert _classify_close(ev("task_completed")) == ("COMPLETED", "task_completed")
    assert _classify_close(ev("user_initiated")) == ("COMPLETED", "user_initiated")
    assert _classify_close(ev("participant_disconnected")) == ("ABORTED", "participant_disconnected")
    assert _classify_close(ev("job_shutdown")) == ("ABORTED", "job_shutdown")
    assert _classify_close(ev("error", error=ValueError("x"))) == ("ABORTED", "error:ValueError")
    assert _classify_close(ev("error")) == ("ABORTED", "error:unknown")
    assert _classify_close(SimpleNamespace(reason=None, error=None)) == ("ABORTED", "unknown")
    assert _classify_close(None) == ("ABORTED", "unknown")


async def _drain(agen):
    async for _ in agen:
        pass
