#!/usr/bin/env python3
"""VoxTicket voice agent worker (LiveKit Agents).

Realtime pipeline, all verified against livekit-agents 1.8.4:

    browser mic --WebRTC--> LiveKit server --WebRTC--> this worker
      AssemblyAI Universal-3.6 Pro streaming STT (auto en/ur + code-switching)
      -> custom llm_node: POSTs the transcript to the VoxTicket Java brain
         (POST /api/v1/voice/turn) and yields the reply text
      -> Cartesia streaming TTS, English/Urdu voice picked per reply
      -> audio back to the browser
    VAD: Silero. Turn detection: LiveKit default audio turn detector.

Design notes (see README for the full rationale):
- preemptive_generation is DISABLED. The Java brain is stateful (OTP attempt
  counting, audit rows, tool calls): speculative pre-turn calls would execute
  real turns twice. This is a correctness requirement, not a tuning knob.
- No LLM provider is configured. A placeholder llm.LLM instance is passed to
  AgentSession because the pipeline silently skips reply generation when llm
  is None ("skip response if no llm is set"); VoxTicketAgent.llm_node is
  fully overridden so the placeholder's chat() is never called. The Java
  backend IS the brain.
- The LiveKit room name is reused as the VoxTicket sessionId, so turns from
  one call share one conversation session with zero Java session changes.
- Greeting uses session.say(), not generate_reply(): saying hello must not
  create a phantom turn in the Java conversation.

Run:
    uv run agent.py dev        # development: worker registers, joins rooms
    uv run agent.py start      # production mode
    uv run agent.py console    # terminal test (needs provider keys)

Env (see .env.example): everything fail-fast validated in load_config().
Secrets are env-only, never files.
"""

from __future__ import annotations

import dataclasses
import logging
import os
import re

import httpx
from dotenv import load_dotenv

# Load .env at import time, not inside load_config(): the LiveKit worker
# itself reads LIVEKIT_URL / LIVEKIT_API_KEY / LIVEKIT_API_SECRET from
# os.environ when it starts up, which happens BEFORE our entrypoint runs.
# Without this, `python agent.py dev` fails with
# "ws_url is required, or set LIVEKIT_URL environment variable"
# even when the values are correctly set in .env.
load_dotenv()

from livekit import agents
from livekit.agents import Agent, AgentServer, AgentSession, llm
from livekit.plugins import assemblyai, cartesia, silero

logger = logging.getLogger("voxticket-voice")


# --------------------------------------------------------------------------
# Config
# --------------------------------------------------------------------------

class ConfigError(Exception):
    """Raised at startup when the environment is misconfigured."""


@dataclasses.dataclass(frozen=True)
class Config:
    livekit_url: str
    livekit_api_key: str
    livekit_api_secret: str
    assemblyai_api_key: str
    cartesia_api_key: str
    cartesia_voice_en: str
    cartesia_voice_ur: str
    cartesia_model: str = "sonic-3"
    voxticket_turn_url: str = "http://localhost:8080/api/v1/voice/turn"
    brain_timeout_s: float = 25.0
    greeting: str = "Welcome to VoxTicket! How can I help you today?"
    log_level: str = "INFO"


_REQUIRED = (
    "LIVEKIT_URL",
    "LIVEKIT_API_KEY",
    "LIVEKIT_API_SECRET",
    "ASSEMBLYAI_API_KEY",
    "CARTESIA_API_KEY",
    "CARTESIA_VOICE_EN",
    "CARTESIA_VOICE_UR",
)


def load_config(env: dict | None = None) -> Config:
    """Read env (.env is already loaded at import time) and fail fast."""
    env = env if env is not None else os.environ
    missing = [k for k in _REQUIRED if not (env.get(k) or "").strip()]
    if missing:
        raise ConfigError(
            "Missing required environment variables: " + ", ".join(missing)
            + ". See worker/.env.example."
        )

    def opt(key: str, default: str) -> str:
        return (env.get(key) or "").strip() or default

    return Config(
        livekit_url=env["LIVEKIT_URL"].strip(),
        livekit_api_key=env["LIVEKIT_API_KEY"].strip(),
        livekit_api_secret=env["LIVEKIT_API_SECRET"].strip(),
        assemblyai_api_key=env["ASSEMBLYAI_API_KEY"].strip(),
        cartesia_api_key=env["CARTESIA_API_KEY"].strip(),
        cartesia_voice_en=env["CARTESIA_VOICE_EN"].strip(),
        cartesia_voice_ur=env["CARTESIA_VOICE_UR"].strip(),
        cartesia_model=opt("CARTESIA_MODEL", "sonic-3"),
        voxticket_turn_url=opt("VOXTICKET_TURN_URL", "http://localhost:8080/api/v1/voice/turn"),
        brain_timeout_s=float(opt("BRAIN_TIMEOUT_S", "25")),
        greeting=opt("GREETING", "Welcome to VoxTicket! How can I help you today?"),
        log_level=opt("LOG_LEVEL", "INFO"),
    )


# --------------------------------------------------------------------------
# Language: which TTS voice for this reply?
# --------------------------------------------------------------------------

_URDU_RE = re.compile(r"[\u0600-\u06FF\u0750-\u077F\uFB50-\uFDFF\uFE70-\uFEFF]")


def is_urdu(text: str) -> bool:
    """True when the reply contains Urdu-script characters.

    The Java brain already mirrors the reply language; the reply's script is
    the cheapest reliable signal for picking the TTS voice, and it needs no
    extra round-trip or protocol change.
    """
    return bool(_URDU_RE.search(text or ""))


# --------------------------------------------------------------------------
# Java brain client
# --------------------------------------------------------------------------

class BrainError(Exception):
    """The VoxTicket Java brain could not produce a reply."""


class VoxTicketBrain:
    """Thin async client for POST /api/v1/voice/turn.

    One turn in -> one reply text out. All conversation state (sessions,
    language, OTP, tools, audit) stays inside the Java backend.
    """

    def __init__(self, turn_url: str, timeout_s: float = 25.0,
                 client: httpx.AsyncClient | None = None):
        self._turn_url = turn_url
        self._client = client or httpx.AsyncClient(timeout=timeout_s)

    async def turn(self, session_id: str, text: str) -> str:
        try:
            resp = await self._client.post(
                self._turn_url, json={"sessionId": session_id, "message": text}
            )
        except httpx.HTTPError as exc:
            raise BrainError(f"could not reach VoxTicket brain at {self._turn_url}: {exc}") from exc
        if resp.status_code != 200:
            raise BrainError(f"brain returned HTTP {resp.status_code}: {resp.text[:200]}")
        try:
            data = resp.json()
        except ValueError as exc:
            raise BrainError("brain returned a non-JSON response") from exc
        reply = (data.get("text") or "").strip()
        if not reply:
            raise BrainError("brain returned an empty reply text")
        return reply

    async def aclose(self) -> None:
        await self._client.aclose()


# --------------------------------------------------------------------------
# Agent: Java brain as the "LLM", Cartesia voice picked per reply
# --------------------------------------------------------------------------

class _PlaceholderLLM(llm.LLM):
    """Stand-in LLM so the pipeline actually generates replies.

    The Java backend IS the brain: VoxTicketAgent overrides llm_node
    completely, so this object's chat() is never invoked. The instance must
    still be non-None because AgentSession silently skips reply generation
    when llm is None ("skip response if no llm is set" in agent_activity).
    """

    def chat(self, *, chat_ctx: llm.ChatContext, tools=None, conn_options=None, **kwargs):
        raise RuntimeError("unreachable: VoxTicketAgent.llm_node is fully overridden")


class VoxTicketAgent(Agent):
    def __init__(self, *, brain: VoxTicketBrain, session_id: str,
                 tts: cartesia.TTS, voice_en: str, voice_ur: str, greeting: str):
        # instructions are unused (no LLM provider) but the base class takes them.
        super().__init__(instructions="You are the voice interface for VoxTicket customer support.")
        self._brain = brain
        self._session_id = session_id
        self._tts = tts
        self._voice_en = voice_en
        self._voice_ur = voice_ur
        self._greeting = greeting
        self._next_voice: tuple[str, str] | None = None  # (voice_id, language) stashed by llm_node

    async def on_enter(self) -> None:
        # Greet directly: this must NOT go through llm_node, or the Java
        # brain would record a phantom turn before the caller says anything.
        self.session.say(self._greeting)

    @staticmethod
    def _user_text(chat_ctx: llm.ChatContext) -> str:
        for item in reversed(chat_ctx.items):
            if isinstance(item, llm.ChatMessage) and item.role == "user":
                return item.text_content or ""
        return ""

    async def llm_node(self, chat_ctx: llm.ChatContext, tools: list, model_settings):
        """The pipeline's "LLM" step: one Java turn per caller turn.

        Yields the full reply as a single text chunk (verified: llm_node may
        yield plain str). Cancellation (barge-in) aborts the in-flight HTTP
        request on this side; the Java turn still completes server-side, which
        is harmless - see README "Barge-in semantics".
        """
        user_text = self._user_text(chat_ctx).strip()
        if not user_text:
            logger.warning(
                "llm_node invoked with empty user text - skipping turn "
                "(no Java brain call made)"
            )
            return
        logger.info(
            "llm_node: turn committed, calling Java brain (session=%s, %d chars)",
            self._session_id, len(user_text),
        )
        try:
            reply = await self._brain.turn(self._session_id, user_text)
        except BrainError as exc:
            logger.error("Java brain turn failed: %s", exc)
            reply = (
                "Sorry, I'm having trouble reaching the service right now. "
                "Please try again in a moment."
            )
        logger.info("llm_node: brain replied (%d chars), yielding to TTS", len(reply))
        # Pick the TTS voice now: tts_node only sees audio-bound text chunks.
        self._next_voice = (self._voice_ur, "ur") if is_urdu(reply) else (self._voice_en, "en")
        yield reply

    async def tts_node(self, text, model_settings):
        """Apply the voice stashed by llm_node, then run the default node."""
        voice_id, language = self._next_voice or (self._voice_en, "en")
        self._next_voice = None
        self._tts.update_options(voice=voice_id, language=language)
        async for frame in Agent.default.tts_node(self, text, model_settings):
            yield frame


# --------------------------------------------------------------------------
# Entrypoint
# --------------------------------------------------------------------------

server = AgentServer()


@server.rtc_session()
async def entrypoint(ctx: agents.JobContext):
    cfg = load_config()
    logging.basicConfig(level=cfg.log_level.upper())

    brain = VoxTicketBrain(cfg.voxticket_turn_url, cfg.brain_timeout_s)
    tts = cartesia.TTS(
        api_key=cfg.cartesia_api_key,
        model=cfg.cartesia_model,
        language="en",  # per-reply override in tts_node
        voice=cfg.cartesia_voice_en,
    )
    agent = VoxTicketAgent(
        brain=brain,
        session_id=ctx.room.name,  # room name == VoxTicket conversation session
        tts=tts,
        voice_en=cfg.cartesia_voice_en,
        voice_ur=cfg.cartesia_voice_ur,
        greeting=cfg.greeting,
    )

    session = AgentSession(
        stt=assemblyai.STT(
            api_key=cfg.assemblyai_api_key,
            language_codes=["en", "ur"],  # steer auto-detection to our two languages
        ),
        vad=silero.VAD.load(),
        tts=tts,
        # Placeholder LLM: non-None is REQUIRED, otherwise the pipeline
        # silently skips reply generation ("skip response if no llm is set").
        # The real brain is VoxTicketAgent.llm_node -> Java backend.
        llm=_PlaceholderLLM(),
        # Default audio turn detector stays on. Preemptive generation MUST stay
        # off: speculative calls would execute real, stateful Java turns twice.
        turn_handling={"preemptive_generation": {"enabled": False}},
    )

    logger.info(
        "Placeholder LLM active; replies come from VoxTicketAgent.llm_node -> Java brain"
    )
    logger.info("Starting voice session for room %s", ctx.room.name)
    await session.start(room=ctx.room, agent=agent)


if __name__ == "__main__":
    # Validate before the worker starts so a misconfigured environment fails
    # here with the friendly ConfigError (naming the missing variable)
    # instead of deep inside the framework's startup.
    load_config()
    agents.cli.run_app(server)
