"""Agent nodes: llm_node delegates to the Java brain and stashes the TTS voice."""

import logging

import pytest

from livekit.agents import llm

from agent import BrainError, VoxTicketAgent, is_urdu


class FakeBrain:
    def __init__(self, reply: str = "ok", error: Exception | None = None,
                 chunks: list[str] | None = None):
        self.reply = reply
        self.error = error
        self.chunks = chunks
        self.calls: list[tuple[str, str]] = []

    async def turn(self, session_id: str, text: str) -> str:
        self.calls.append((session_id, text))
        if self.error is not None:
            raise self.error
        return self.reply

    async def turn_stream(self, session_id: str, text: str):
        self.calls.append((session_id, text))
        if self.error is not None:
            raise self.error
        for chunk in self.chunks if self.chunks is not None else [self.reply]:
            yield chunk


class FakeTTS:
    def __init__(self):
        self.options: list[tuple[str, str]] = []

    def update_options(self, *, voice=None, language=None, **kwargs):
        self.options.append((voice, language))


def make_agent(reply="ok", error=None, chunks=None) -> VoxTicketAgent:
    return VoxTicketAgent(
        brain=FakeBrain(reply, error, chunks),
        session_id="room-7",
        tts=FakeTTS(),
        voice_en="voice-en",
        voice_ur="voice-ur",
        greeting="hi",
    )


def user_ctx(text: str) -> llm.ChatContext:
    ctx = llm.ChatContext()
    ctx.add_message(role="user", content=text)
    return ctx


def test_user_text_extracts_latest_user_message():
    agent = make_agent()
    ctx = user_ctx("my order status")
    assert agent._user_text(ctx) == "my order status"


def test_user_text_empty_when_no_user_message():
    agent = make_agent()
    assert agent._user_text(llm.ChatContext()) == ""


@pytest.mark.asyncio
async def test_llm_node_yields_brain_reply_and_posts_room_as_session():
    agent = make_agent(chunks=["Your order ", "is on its way."])
    chunks = [c async for c in agent.llm_node(user_ctx("where is my order"), [], None)]
    assert chunks == ["Your order ", "is on its way."]
    assert agent._brain.calls == [("room-7", "where is my order")]


@pytest.mark.asyncio
async def test_llm_node_stashes_urdu_voice_for_urdu_reply():
    agent = make_agent(reply="آپ کا آرڈر راستے میں ہے۔")
    await _drain(agent.llm_node(user_ctx("میرا آرڈر کہاں ہے"), [], None))
    assert agent._next_voice == ("voice-ur", "ur")


@pytest.mark.asyncio
async def test_llm_node_stashes_english_voice_for_english_reply():
    agent = make_agent(reply="Your order is on its way.")
    await _drain(agent.llm_node(user_ctx("where is my order"), [], None))
    assert agent._next_voice == ("voice-en", "en")


@pytest.mark.asyncio
async def test_llm_node_falls_back_to_spoken_apology_when_brain_fails():
    agent = make_agent(error=BrainError("down"))
    chunks = [c async for c in agent.llm_node(user_ctx("hello"), [], None)]
    assert len(chunks) == 1
    assert "trouble" in chunks[0]
    # the apology is English: English voice must be stashed, never Urdu
    assert agent._next_voice == ("voice-en", "en")
    assert not is_urdu(chunks[0])


@pytest.mark.asyncio
async def test_llm_node_yields_nothing_for_empty_transcript(caplog):
    agent = make_agent()
    with caplog.at_level(logging.WARNING, logger="voxticket-voice"):
        chunks = [c async for c in agent.llm_node(llm.ChatContext(), [], None)]
    assert chunks == []
    assert agent._brain.calls == []
    assert "empty user text" in caplog.text


@pytest.mark.asyncio
async def test_llm_node_logs_brain_roundtrip(caplog):
    agent = make_agent(reply="hello back")
    with caplog.at_level(logging.INFO, logger="voxticket-voice"):
        await _drain(agent.llm_node(user_ctx("hi"), [], None))
    assert "turn committed, streaming Java brain" in caplog.text
    assert "brain stream ended" in caplog.text


@pytest.mark.asyncio
async def test_llm_node_locks_voice_from_first_chunk():
    # First chunk decides the turn's voice, even if later chunks differ.
    agent = make_agent(chunks=["Sure. ", "جی بالکل۔"])
    chunks = [c async for c in agent.llm_node(user_ctx("hi"), [], None)]
    assert chunks == ["Sure. ", "جی بالکل۔"]
    assert agent._next_voice == ("voice-en", "en")


@pytest.mark.asyncio
async def test_llm_node_logs_locked_voice_and_language(caplog):
    agent = make_agent(chunks=["جی بالکل۔ مدد کرتا ہوں۔"])
    with caplog.at_level(logging.INFO, logger="voxticket-voice"):
        await _drain(agent.llm_node(user_ctx("salam"), [], None))
    assert "TTS voice locked voice_id=voice-ur language=ur" in caplog.text


@pytest.mark.asyncio
async def test_llm_node_no_apology_after_partial_deltas():
    # A mid-stream failure can't unsay what's already spoken: no apology.
    class FailAfter:
        def __init__(self):
            self.calls = []

        async def turn_stream(self, session_id, text):
            self.calls.append((session_id, text))
            yield "partial "
            raise BrainError("stream died")

    agent = make_agent()
    agent._brain = FailAfter()
    chunks = [c async for c in agent.llm_node(user_ctx("hi"), [], None)]
    assert chunks == ["partial "]


async def _drain(agen):
    async for _ in agen:
        pass


@pytest.mark.asyncio
async def test_tts_node_falls_back_to_english_when_urdu_voice_fails(monkeypatch, caplog):
    """A bad CARTESIA_VOICE_UR makes Cartesia push zero audio frames; the
    node must degrade to the English voice instead of leaving silence."""
    from livekit.agents._exceptions import APIError
    from livekit.agents.voice.agent import Agent as VoiceAgent

    attempts = {"n": 0}

    async def fake_default_tts_node(self, text, model_settings):
        attempts["n"] += 1
        if attempts["n"] == 1:
            raise APIError("no audio frames were pushed")
        yield "frame"

    monkeypatch.setattr(VoiceAgent.default, "tts_node", fake_default_tts_node)

    agent = make_agent()
    agent._next_voice = ("voice-ur", "ur")
    with caplog.at_level(logging.WARNING, logger="voxticket-voice"):
        frames = [f async for f in agent.tts_node("سلام", None)]

    assert frames == ["frame"]
    assert attempts["n"] == 2
    # first the Urdu voice, then the English fallback
    assert agent._tts.options == [("voice-ur", "ur"), ("voice-en", "en")]
    assert "falling back to English voice" in caplog.text


@pytest.mark.asyncio
async def test_tts_node_does_not_fallback_for_english_voice(monkeypatch):
    """An English synthesis failure must propagate, not loop into fallback."""
    from livekit.agents._exceptions import APIError
    from livekit.agents.voice.agent import Agent as VoiceAgent

    async def failing_tts_node(self, text, model_settings):
        raise APIError("no audio frames were pushed")
        yield  # pragma: no cover - make this an async generator

    monkeypatch.setattr(VoiceAgent.default, "tts_node", failing_tts_node)

    agent = make_agent()
    agent._next_voice = ("voice-en", "en")
    with pytest.raises(APIError):
        [f async for f in agent.tts_node("hello", None)]
    assert agent._tts.options == [("voice-en", "en")]
