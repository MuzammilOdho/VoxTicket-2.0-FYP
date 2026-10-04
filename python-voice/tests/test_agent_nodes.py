"""Agent nodes: llm_node delegates to the Java brain and stashes the TTS voice."""

import pytest

from livekit.agents import llm

from agent import BrainError, VoxTicketAgent, is_urdu


class FakeBrain:
    def __init__(self, reply: str = "ok", error: Exception | None = None):
        self.reply = reply
        self.error = error
        self.calls: list[tuple[str, str]] = []

    async def turn(self, session_id: str, text: str) -> str:
        self.calls.append((session_id, text))
        if self.error is not None:
            raise self.error
        return self.reply


class FakeTTS:
    def __init__(self):
        self.options: list[tuple[str, str]] = []

    def update_options(self, *, voice=None, language=None, **kwargs):
        self.options.append((voice, language))


def make_agent(reply="ok", error=None) -> VoxTicketAgent:
    return VoxTicketAgent(
        brain=FakeBrain(reply, error),
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
    agent = make_agent(reply="Your order is on its way.")
    chunks = [c async for c in agent.llm_node(user_ctx("where is my order"), [], None)]
    assert chunks == ["Your order is on its way."]
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
async def test_llm_node_yields_nothing_for_empty_transcript():
    agent = make_agent()
    chunks = [c async for c in agent.llm_node(llm.ChatContext(), [], None)]
    assert chunks == []
    assert agent._brain.calls == []


async def _drain(agen):
    async for _ in agen:
        pass
