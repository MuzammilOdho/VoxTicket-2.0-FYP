#!/usr/bin/env python3
"""VoxTicket voice agent worker (LiveKit Agents).

Realtime pipeline, all verified against livekit-agents 1.8.4:

    browser mic --WebRTC--> LiveKit server --WebRTC--> this worker
      AssemblyAI Universal-3.6 Pro streaming STT (auto en/ur + code-switching,
        keyterms_prompt biases the decoder toward VoxTicket vocabulary)
      -> custom llm_node: streams the VoxTicket Java brain
         (POST /api/v1/voice/turn/stream, SSE deltas) and yields each delta
      -> Cartesia Sonic-3.6 streaming TTS (Urdu is only supported from 3.6),
         English/Urdu voice picked per reply, Urdu-aware sentence chunking
         so first audio isn't gated on the full reply
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
import json
import logging
import os
import re
from typing import AsyncIterator

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
from livekit.agents.tokenize import SentenceStream, SentenceTokenizer, token_stream
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
    # sonic-3.6+: Urdu (ur) is only supported from 3.6 on - sonic-3 cannot
    # synthesize Urdu at all (zero audio frames). Drop-in compatible.
    cartesia_model: str = "sonic-3.6"
    assemblyai_keyterms: tuple = ()
    voxticket_turn_url: str = "http://localhost:8080/api/v1/voice/turn"
    voxticket_turn_stream_url: str = "http://localhost:8080/api/v1/voice/turn/stream"
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

    def opt_list(key: str, default: str) -> tuple:
        raw = (env.get(key) or "").strip() or default
        return tuple(t.strip() for t in raw.split(",") if t.strip())

    # Domain keyterms bias the STT decoder toward VoxTicket vocabulary
    # (brand name, OTP spelling, support verbs). Max 2048 chars total.
    default_keyterms = "VoxTicket,OTP,order,refund,delivery,tracking,payment,verification code"

    return Config(
        livekit_url=env["LIVEKIT_URL"].strip(),
        livekit_api_key=env["LIVEKIT_API_KEY"].strip(),
        livekit_api_secret=env["LIVEKIT_API_SECRET"].strip(),
        assemblyai_api_key=env["ASSEMBLYAI_API_KEY"].strip(),
        cartesia_api_key=env["CARTESIA_API_KEY"].strip(),
        cartesia_voice_en=env["CARTESIA_VOICE_EN"].strip(),
        cartesia_voice_ur=env["CARTESIA_VOICE_UR"].strip(),
        cartesia_model=opt("CARTESIA_MODEL", "sonic-3.6"),
        assemblyai_keyterms=opt_list("ASSEMBLYAI_KEYTERMS", default_keyterms),
        voxticket_turn_url=opt("VOXTICKET_TURN_URL", "http://localhost:8080/api/v1/voice/turn"),
        voxticket_turn_stream_url=opt("VOXTICKET_TURN_STREAM_URL", "http://localhost:8080/api/v1/voice/turn/stream"),
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
# Sentence splitting: Urdu-aware (Cartesia chunks synthesis per sentence)
# --------------------------------------------------------------------------

_SENTENCE_BOUNDARY_RE = re.compile(r"[۔؟!?.;:\n]")


def _split_sentences(text: str) -> list[tuple[str, int, int]]:
    """Split into (sentence, start, end) on Urdu + Latin boundaries.

    The stock tokenizers don't split on Urdu full stop U+06D4 (۔) or question
    mark U+061F (؟), so a whole Urdu reply would reach TTS as one chunk and
    streaming would buy nothing. Slight over-splitting (e.g. decimals) is
    harmless: the buffered stream holds tiny fragments until more context
    arrives, and shorter synthesis requests are still correct audio.
    """
    out: list[tuple[str, int, int]] = []
    start = 0
    for match in _SENTENCE_BOUNDARY_RE.finditer(text):
        end = match.end()
        sentence = text[start:end].strip()
        if sentence:
            out.append((sentence, start, end))
        start = end
    tail = text[start:].strip()
    if tail:
        out.append((tail, start, len(text)))
    return out


class UrduAwareSentenceTokenizer(SentenceTokenizer):
    """SentenceTokenizer that also breaks on Urdu ۔ and ؟.

    Passed to the Cartesia TTS plugin, which chunks its synthesis requests
    per emitted sentence - so the first Urdu sentence speaks while later
    ones are still generating.
    """

    def tokenize(self, text: str, *, language: str | None = None) -> list[str]:
        return [sentence for sentence, _, _ in _split_sentences(text)]

    def stream(self, *, language: str | None = None) -> SentenceStream:
        return token_stream.BufferedSentenceStream(
            tokenizer=_split_sentences,
            # Emit a complete sentence as soon as the next chunk proves it
            # complete: sentences like "جی ہاں، بالکل۔" must not wait for
            # a long buffer before first audio. Tiny fragments still gather
            # a little context before going out.
            min_token_len=10,
            min_ctx_len=10,
        )


# --------------------------------------------------------------------------
# Java brain client
# --------------------------------------------------------------------------

class BrainError(Exception):
    """The VoxTicket Java brain could not produce a reply."""


class VoxTicketBrain:
    """Thin async client for the VoxTicket Java brain.

    One turn in -> reply text out. All conversation state (sessions,
    language, OTP, tools, audit) stays inside the Java backend.

    Two endpoints:
    - POST /api/v1/voice/turn        (blocking JSON; legacy / fallback)
    - POST /api/v1/voice/turn/stream (SSE: data: {"delta": "..."} ... data: {"done": true})
    """

    def __init__(self, turn_url: str, timeout_s: float = 25.0,
                 client: httpx.AsyncClient | None = None,
                 stream_url: str | None = None):
        self._turn_url = turn_url
        self._stream_url = stream_url or turn_url.rstrip("/").removesuffix("/turn") + "/turn/stream"
        self._client = client or httpx.AsyncClient(timeout=timeout_s)
        # SSE reads must survive slow tokens: generous per-read timeout, and
        # barge-in cancellation closes the stream from the consumer side.
        self._stream_client = client or httpx.AsyncClient(
            timeout=httpx.Timeout(60.0, connect=10.0))

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

    async def turn_stream(self, session_id: str, text: str) -> AsyncIterator[str]:
        """Yield reply deltas from the SSE stream endpoint.

        Falls back to the blocking endpoint (yielded as one chunk) when the
        Java side predates the stream endpoint (404/405). Raises BrainError
        on failure; cancelling the consumer (barge-in) closes the stream,
        which lets the Java side abort the turn and release its session lock.
        """
        url = self._stream_url
        try:
            async with self._stream_client.stream(
                "POST", url, json={"sessionId": session_id, "message": text}
            ) as resp:
                if resp.status_code in (404, 405):
                    logger.info("brain stream endpoint unavailable (HTTP %s); falling back to blocking turn",
                                resp.status_code)
                    yield await self.turn(session_id, text)
                    return
                if resp.status_code != 200:
                    body = await resp.aread()
                    raise BrainError(f"brain stream returned HTTP {resp.status_code}: {body[:200]!r}")
                async for line in resp.aiter_lines():
                    line = line.strip()
                    if not line.startswith("data:"):
                        continue
                    payload = line[len("data:"):].strip()
                    if not payload:
                        continue
                    try:
                        event = json.loads(payload)
                    except ValueError:
                        continue
                    if not isinstance(event, dict):
                        continue
                    if event.get("done"):
                        break
                    delta = event.get("delta")
                    if delta:
                        yield delta
        except httpx.HTTPError as exc:
            raise BrainError(f"could not stream from VoxTicket brain at {url}: {exc}") from exc

    async def aclose(self) -> None:
        await self._client.aclose()
        if self._stream_client is not self._client:
            await self._stream_client.aclose()


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
        """The pipeline's "LLM" step: one Java turn per caller turn, streamed.

        Deltas from the Java SSE stream are yielded as they arrive, so TTS
        starts on the first sentence while the rest generates. The TTS voice
        is picked from the first non-empty delta's script and locked for the
        turn (the full reply isn't known upfront anymore).

        Cancellation (barge-in) aborts the in-flight HTTP stream on this
        side; the Java side notices the disconnect on its next emit, drops
        the partial reply, and releases its per-session lock.
        """
        user_text = self._user_text(chat_ctx).strip()
        if not user_text:
            logger.warning(
                "llm_node invoked with empty user text - skipping turn "
                "(no Java brain call made)"
            )
            return
        logger.info(
            "llm_node: turn committed, streaming Java brain (session=%s, %d chars)",
            self._session_id, len(user_text),
        )
        voice_locked = False
        yielded_any = False
        try:
            async for delta in self._brain.turn_stream(self._session_id, user_text):
                if not voice_locked and delta.strip():
                    # First real content decides the turn's TTS voice.
                    self._next_voice = (self._voice_ur, "ur") if is_urdu(delta) else (self._voice_en, "en")
                    voice_locked = True
                    logger.info("llm_node: TTS voice locked voice_id=%s language=%s (first delta %r)",
                                self._next_voice[0], self._next_voice[1], delta[:60])
                yielded_any = True
                yield delta
        except BrainError as exc:
            logger.error("Java brain turn failed: %s", exc)
            if not yielded_any:
                # Nothing spoken yet: the apology can still take the turn.
                # After speech started, partial audio can't be unsaid.
                yield (
                    "Sorry, I'm having trouble reaching the service right now. "
                    "Please try again in a moment."
                )
        if not voice_locked:
            self._next_voice = (self._voice_en, "en")
        logger.info("llm_node: brain stream ended (yielded_any=%s)", yielded_any)

    async def tts_node(self, text, model_settings):
        """Apply the voice stashed by llm_node, then run the default node.

        If the Urdu voice fails to synthesize (e.g. CARTESIA_VOICE_UR is not
        a voice that supports Urdu, Cartesia returns zero audio frames),
        fall back to the English voice once instead of leaving the caller
        in silence. The warning names the culprit so the misconfiguration
        still gets fixed.
        """
        voice_id, language = self._next_voice or (self._voice_en, "en")
        self._next_voice = None
        self._tts.update_options(voice=voice_id, language=language)
        logger.info("tts_node: synthesizing with voice_id=%s language=%s", voice_id, language)
        try:
            async for frame in Agent.default.tts_node(self, text, model_settings):
                yield frame
        except Exception as exc:
            if language != "ur":
                raise
            logger.warning(
                "Urdu TTS voice %r failed (%s); falling back to English voice",
                voice_id, exc,
            )
            self._tts.update_options(voice=self._voice_en, language="en")
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

    brain = VoxTicketBrain(cfg.voxticket_turn_url, cfg.brain_timeout_s,
                           stream_url=cfg.voxticket_turn_stream_url)
    tts = cartesia.TTS(
        api_key=cfg.cartesia_api_key,
        model=cfg.cartesia_model,
        language="en",  # per-reply override in tts_node
        voice=cfg.cartesia_voice_en,
        # Urdu-aware sentence chunking: the stock tokenizer doesn't split on
        # ۔/؟, which would delay all Urdu audio until the full reply arrives.
        tokenizer=UrduAwareSentenceTokenizer(),
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
            # Bias the decoder toward VoxTicket vocabulary (brand name, OTP,
            # support verbs). Helps most where the model is weakest (Urdu).
            keyterms_prompt=list(cfg.assemblyai_keyterms),
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
