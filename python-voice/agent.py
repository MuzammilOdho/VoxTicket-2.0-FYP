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

import asyncio
import dataclasses
import json
import logging
import os
import re
import socket
import time
import uuid
from datetime import datetime, timezone
from typing import AsyncIterator

# Windows-only: the soxr resampler bundled inside livekit_ffi.dll crashes with
# "Assertion failed: LSX_FFT_BR_ == NULL" (soxr-sys fft4g_cache.h) when it
# resamples audio on multiple threads - e.g. 24 kHz Cartesia TTS output <->
# 48 kHz WebRTC, which happens on every spoken reply. Forcing single-threaded
# resampling avoids the race.
#
# This MUST sit here, immediately after `import os` and before ANY livekit
# import: livekit_ffi.dll loads (and soxr picks up this variable) the moment
# `livekit` is first imported, so setting it any later has no effect in that
# process. setdefault: an explicitly configured value always wins.
os.environ.setdefault("SOXR_MAX_THREADS", "1")

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
from livekit.agents import stt as stt_api
from livekit.agents import tts as tts_api
from livekit.agents.tokenize import SentenceStream, SentenceTokenizer, token_stream
# All provider plugins are imported at module top level (main thread).
# livekit-agents requires plugin registration on the main thread, so the
# imports cannot be lazy inside the factories - only the *selection* of
# which provider to construct is dynamic (STT_PROVIDER / TTS_PROVIDER).
from livekit.plugins import assemblyai, azure, cartesia, elevenlabs, silero

logger = logging.getLogger("voxticket-voice")


# --------------------------------------------------------------------------
# Config
# --------------------------------------------------------------------------

class ConfigError(Exception):
    """Raised at startup when the environment is misconfigured."""


# Every model is selectable and replaceable, mirroring the Java
# TierChatClientRegistry posture: a selected provider whose keys are missing
# fails startup loudly (naming the key) instead of limping on.
STT_PROVIDERS = ("assemblyai", "elevenlabs")
TTS_PROVIDERS = ("cartesia", "elevenlabs", "azure")


@dataclasses.dataclass(frozen=True)
class Config:
    livekit_url: str
    livekit_api_key: str
    livekit_api_secret: str
    # Provider selection (STT_PROVIDER / TTS_PROVIDER env).
    stt_provider: str = "assemblyai"
    tts_provider: str = "cartesia"
    # AssemblyAI STT (required when STT_PROVIDER=assemblyai).
    assemblyai_api_key: str = ""
    assemblyai_keyterms: tuple = ()
    # ElevenLabs STT via Scribe v2 Realtime (required: ELEVENLABS_API_KEY when
    # STT_PROVIDER=elevenlabs). Primary language hint + secondary languages
    # keep English primary while still hearing Urdu.
    elevenlabs_api_key: str = ""
    elevenlabs_stt_model: str = "scribe_v2_realtime"
    elevenlabs_stt_language: str = "en"
    elevenlabs_stt_secondary_languages: tuple = ("ur",)
    # Cartesia TTS (required: key + both voices when TTS_PROVIDER=cartesia).
    cartesia_api_key: str = ""
    cartesia_voice_en: str = ""
    cartesia_voice_ur: str = ""
    # sonic-3.6+: Urdu (ur) is only supported from 3.6 on - sonic-3 cannot
    # synthesize Urdu at all (zero audio frames). Drop-in compatible.
    cartesia_model: str = "sonic-3.6"
    # ElevenLabs TTS (required: key + both voices when TTS_PROVIDER=elevenlabs).
    # eleven_v3 explicitly supports Urdu; flash models are faster but their
    # Urdu support is unverified - hence v3 is the default.
    elevenlabs_tts_model: str = "eleven_v3"
    elevenlabs_voice_en: str = ""
    elevenlabs_voice_ur: str = ""
    # Azure TTS (required: key + region when TTS_PROVIDER=azure; voices have
    # well-known defaults because Azure voice names are stable identifiers).
    azure_speech_key: str = ""
    azure_speech_region: str = ""
    azure_voice_en: str = "en-US-AvaMultilingualNeural"
    azure_voice_ur: str = "ur-PK-AsadNeural"
    voxticket_turn_url: str = "http://localhost:8080/api/v1/voice/turn"
    voxticket_turn_stream_url: str = "http://localhost:8080/api/v1/voice/turn/stream"
    brain_timeout_s: float = 25.0
    # P3 voice telemetry (all optional, fail-open: telemetry must never break
    # the call). Flush/heartbeat loops run as background asyncio tasks; the
    # audio pipeline never awaits them.
    voxticket_telemetry_url: str = "http://localhost:8080/api/v1/voice/telemetry"
    voxticket_heartbeat_url: str = "http://localhost:8080/api/v1/voice/worker-heartbeat"
    voice_telemetry_secret: str = ""
    telemetry_flush_seconds: float = 5.0
    heartbeat_seconds: float = 30.0
    worker_id: str = ""  # default: <hostname>-<pid>, resolved in entrypoint
    greeting: str = "Welcome to VoxTicket! How can I help you today?"
    log_level: str = "INFO"
    # Silero VAD speech-onset threshold (0.0-1.0). 0.4 aligns with
    # AssemblyAI's internal VAD default (0.4): the two VADs agree on speech
    # onset, so turn detection and STT endpointing don't fight each other.
    # Tuning: lower (0.25-0.35) if soft speech is missed; higher (0.45-0.6)
    # if keyboard/fan/traffic false-triggers turns. Change in 0.05 steps and
    # re-run the voice-quality matrix; adaptive interruption guards the
    # extra false triggers a lower value admits.
    vad_activation_threshold: float = 0.4
    # Seconds after the agent starts speaking during which caller audio is
    # ignored for interruption (AEC warmup: prevents the agent's own voice
    # from false-triggering barge-in before echo cancellation settles).
    # Lower = more responsive barge-in; higher = fewer false interruptions
    # on echoey setups. LiveKit default is 3.0.
    aec_warmup_duration_s: float = 3.0
    # If VAD hears speech but no STT final transcript arrives within this
    # many seconds, the worker speaks the transcription-reprompt (below)
    # instead of leaving the caller in silence. 0 disables.
    transcription_timeout_s: float = 8.0
    transcription_reprompt: str = (
        "Sorry, I didn't catch that. Could you please say it again?"
    )


def load_config(env: dict | None = None) -> Config:
    """Read env (.env is already loaded at import time) and fail fast.

    Only the selected providers' keys are required: STT_PROVIDER=elevenlabs
    must not demand an AssemblyAI key, and vice versa. A selected provider
    with a missing key raises ConfigError naming the key - the same
    fail-closed posture as the Java provider registry.
    """
    env = env if env is not None else os.environ

    def opt(key: str, default: str) -> str:
        return (env.get(key) or "").strip() or default

    def opt_list(key: str, default: str) -> tuple:
        raw = (env.get(key) or "").strip() or default
        return tuple(t.strip() for t in raw.split(",") if t.strip())

    def opt_float(key: str, default: float) -> float:
        raw = opt(key, str(default))
        try:
            return float(raw)
        except ValueError:
            raise ConfigError(f"{key} must be a number, got {raw!r}")

    def require_keys(provider_label: str, *keys: str) -> None:
        missing = [k for k in keys if not (env.get(k) or "").strip()]
        if missing:
            raise ConfigError(
                "Missing required environment variables for "
                + provider_label + ": " + ", ".join(missing)
                + ". See worker/.env.example."
            )

    stt_provider = opt("STT_PROVIDER", "assemblyai").lower()
    if stt_provider not in STT_PROVIDERS:
        raise ConfigError(f"STT_PROVIDER must be one of {list(STT_PROVIDERS)}, got {stt_provider!r}")
    tts_provider = opt("TTS_PROVIDER", "cartesia").lower()
    if tts_provider not in TTS_PROVIDERS:
        raise ConfigError(f"TTS_PROVIDER must be one of {list(TTS_PROVIDERS)}, got {tts_provider!r}")

    # LiveKit is always required; provider keys only for the selected providers.
    require_keys("LiveKit", "LIVEKIT_URL", "LIVEKIT_API_KEY", "LIVEKIT_API_SECRET")
    if stt_provider == "assemblyai":
        require_keys("STT_PROVIDER=assemblyai", "ASSEMBLYAI_API_KEY")
    elif stt_provider == "elevenlabs":
        require_keys("STT_PROVIDER=elevenlabs", "ELEVENLABS_API_KEY")
    if tts_provider == "cartesia":
        require_keys("TTS_PROVIDER=cartesia", "CARTESIA_API_KEY", "CARTESIA_VOICE_EN", "CARTESIA_VOICE_UR")
    elif tts_provider == "elevenlabs":
        require_keys("TTS_PROVIDER=elevenlabs", "ELEVENLABS_API_KEY", "ELEVENLABS_VOICE_EN", "ELEVENLABS_VOICE_UR")
    elif tts_provider == "azure":
        require_keys("TTS_PROVIDER=azure", "AZURE_SPEECH_KEY", "AZURE_SPEECH_REGION")

    # Domain keyterms bias the STT decoder toward VoxTicket vocabulary
    # (brand name, OTP spelling, support verbs). Max 2048 chars total.
    default_keyterms = "VoxTicket,OTP,order,refund,delivery,tracking,payment,verification code"

    return Config(
        livekit_url=env["LIVEKIT_URL"].strip(),
        livekit_api_key=env["LIVEKIT_API_KEY"].strip(),
        livekit_api_secret=env["LIVEKIT_API_SECRET"].strip(),
        stt_provider=stt_provider,
        tts_provider=tts_provider,
        assemblyai_api_key=opt("ASSEMBLYAI_API_KEY", ""),
        assemblyai_keyterms=opt_list("ASSEMBLYAI_KEYTERMS", default_keyterms),
        elevenlabs_api_key=opt("ELEVENLABS_API_KEY", ""),
        elevenlabs_stt_model=opt("ELEVENLABS_STT_MODEL", "scribe_v2_realtime"),
        elevenlabs_stt_language=opt("ELEVENLABS_STT_LANGUAGE", "en"),
        elevenlabs_stt_secondary_languages=opt_list("ELEVENLABS_STT_SECONDARY_LANGUAGES", "ur"),
        elevenlabs_tts_model=opt("ELEVENLABS_TTS_MODEL", "eleven_v3"),
        elevenlabs_voice_en=opt("ELEVENLABS_VOICE_EN", ""),
        elevenlabs_voice_ur=opt("ELEVENLABS_VOICE_UR", ""),
        cartesia_api_key=opt("CARTESIA_API_KEY", ""),
        cartesia_voice_en=opt("CARTESIA_VOICE_EN", ""),
        cartesia_voice_ur=opt("CARTESIA_VOICE_UR", ""),
        cartesia_model=opt("CARTESIA_MODEL", "sonic-3.6"),
        azure_speech_key=opt("AZURE_SPEECH_KEY", ""),
        azure_speech_region=opt("AZURE_SPEECH_REGION", ""),
        azure_voice_en=opt("AZURE_VOICE_EN", "en-US-AvaMultilingualNeural"),
        azure_voice_ur=opt("AZURE_VOICE_UR", "ur-PK-AsadNeural"),
        voxticket_turn_url=opt("VOXTICKET_TURN_URL", "http://localhost:8080/api/v1/voice/turn"),
        voxticket_turn_stream_url=opt("VOXTICKET_TURN_STREAM_URL", "http://localhost:8080/api/v1/voice/turn/stream"),
        brain_timeout_s=opt_float("BRAIN_TIMEOUT_S", 25.0),
        voxticket_telemetry_url=opt("VOXTICKET_TELEMETRY_URL", "http://localhost:8080/api/v1/voice/telemetry"),
        voxticket_heartbeat_url=opt("VOXTICKET_HEARTBEAT_URL", "http://localhost:8080/api/v1/voice/worker-heartbeat"),
        voice_telemetry_secret=opt("VOICE_TELEMETRY_SECRET", ""),
        telemetry_flush_seconds=opt_float("TELEMETRY_FLUSH_SECONDS", 5.0),
        heartbeat_seconds=opt_float("HEARTBEAT_SECONDS", 30.0),
        worker_id=opt("WORKER_ID", ""),
        greeting=opt("GREETING", "Welcome to VoxTicket! How can I help you today?"),
        log_level=opt("LOG_LEVEL", "INFO"),
        vad_activation_threshold=opt_float("VAD_ACTIVATION_THRESHOLD", 0.4),
        aec_warmup_duration_s=opt_float("AEC_WARMUP_DURATION_S", 3.0),
        transcription_timeout_s=opt_float("TRANSCRIPTION_TIMEOUT_S", 8.0),
        transcription_reprompt=opt(
            "TRANSCRIPTION_REPROMPT",
            "Sorry, I didn't catch that. Could you please say it again?",
        ),
    )


# --------------------------------------------------------------------------
# Language: which TTS voice for this reply?
# --------------------------------------------------------------------------

_URDU_RE = re.compile(r"[\u0600-\u06FF\u0750-\u077F\uFB50-\uFDFF\uFE70-\uFEFF\u0900-\u097F]")


def is_urdu(text: str) -> bool:
    """True when the reply contains Urdu-script (or Devanagari) characters.

    The Java brain already mirrors the reply language; the reply's script is
    the cheapest reliable signal for picking the TTS voice, and it needs no
    extra round-trip or protocol change.

    Devanagari is included because AssemblyAI detects spoken Urdu/Hindi
    (Hindustani) as ``"hi"`` and transcribes it in Devanagari script, while
    this app serves it with the Urdu voice. Without this, a Devanagari reply
    would wrongly lock the English voice.
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


# HTTP 429 ("session busy") from the Java brain means the previous
# (barge-in-aborted) turn is still unwinding and holds the session lock;
# the new turn retries rather than speaking an apology to the caller.
# Total worst-case added latency: 2 retries x 0.5s = 1s, on top of the
# Java-side 2s lock-wait budget.
_BRAIN_429_MAX_ATTEMPTS = 3  # total POST attempts, including the first
_BRAIN_429_RETRY_DELAY_S = 0.5


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

    async def turn(self, session_id: str, text: str,
                   *, traceparent: str | None = None,
                   customer_phone: str | None = None) -> str:
        """Blocking turn. traceparent is a best-effort W3C correlation header;
        a missing/invalid value never fails the call (headers=None).
        customer_phone is the caller's backend-minted identity (E.164), read
        back from the LiveKit room; omitted when unknown (anonymous turn)."""
        headers = {"traceparent": traceparent} if traceparent else None
        payload = {"sessionId": session_id, "message": text}
        if customer_phone:
            payload["customerPhone"] = customer_phone
        try:
            resp = await self._client.post(
                self._turn_url, json=payload,
                headers=headers,
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

    async def turn_stream(self, session_id: str, text: str,
                          *, traceparent: str | None = None,
                          customer_phone: str | None = None) -> AsyncIterator[str]:
        """Yield reply deltas from the SSE stream endpoint.

        Falls back to the blocking endpoint (yielded as one chunk) when the
        Java side predates the stream endpoint (404/405). Retries up to
        _BRAIN_429_MAX_ATTEMPTS times on HTTP 429 (session busy: the previous
        barge-in-aborted turn is still unwinding on the Java side). Raises
        BrainError on other failures; cancelling the consumer (barge-in)
        closes the stream, which lets the Java side abort the turn and
        release its session lock.

        traceparent is forwarded on both the stream and the fallback call so
        the Java side can correlate the whole turn. customer_phone (the
        caller's backend-minted E.164 identity) is forwarded the same way;
        omitted when unknown.
        """
        url = self._stream_url
        headers = {"traceparent": traceparent} if traceparent else None
        attempt = 0
        while True:
            attempt += 1
            try:
                payload = {"sessionId": session_id, "message": text}
                if customer_phone:
                    payload["customerPhone"] = customer_phone
                async with self._stream_client.stream(
                        "POST", url, json=payload,
                        headers=headers,
                ) as resp:
                    if resp.status_code in (404, 405):
                        logger.info("brain stream endpoint unavailable (HTTP %s); falling back to blocking turn",
                                    resp.status_code)
                        yield await self.turn(session_id, text, traceparent=traceparent,
                                                customer_phone=customer_phone)
                        return
                    if resp.status_code == 429 and attempt < _BRAIN_429_MAX_ATTEMPTS:
                        # The previous turn still holds the Java session lock.
                        # Retry the turn instead of failing: the abort is
                        # landing and the lock is about to release.
                        await resp.aread()  # release the connection
                        logger.info("brain session busy (HTTP 429); retrying turn in %.1fs (attempt %d of %d)",
                                    _BRAIN_429_RETRY_DELAY_S, attempt + 1, _BRAIN_429_MAX_ATTEMPTS)
                        await asyncio.sleep(_BRAIN_429_RETRY_DELAY_S)
                        continue
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
                    return
            except httpx.HTTPError as exc:
                raise BrainError(f"could not stream from VoxTicket brain at {url}: {exc}") from exc

    async def aclose(self) -> None:
        await self._client.aclose()
        if self._stream_client is not self._client:
            await self._stream_client.aclose()


# --------------------------------------------------------------------------
# P3 voice telemetry: tap points, correlation, background buffer
# --------------------------------------------------------------------------
#
# Design: every tap point is pure timestamp capture (time.perf_counter())
# with no awaits and no I/O, so the audio pipeline can never be delayed by
# telemetry. Per-turn timings accumulate in a mutable TurnTelemetry record;
# a background TelemetryBuffer task POSTs batches to the Java backend every
# TELEMETRY_FLUSH_SECONDS. Any failure (network, Java down, bad status)
# drops the batch and logs ONE throttled warning - the call continues.
#
# Correlation: entrypoint mints one trace_id (uuid4 hex) per call (= room);
# each turn gets a 16-hex span id; brain POSTs carry W3C
# `traceparent: 00-<trace_id>-<span_id>-01`. Header building is best-effort
# and never fails the call.

_TELEMETRY_MAX_TURNS = 1000   # bounded memory: drop-oldest past this
_TELEMETRY_MAX_CALLS = 200
_TELEMETRY_WARN_THROTTLE_S = 60.0  # at most one flush-failure warning/minute
_TURN_FINALIZE_TIMEOUT_S = 60.0    # flush a turn even if tts_node never finalized it


def _build_traceparent(trace_id: str | None, span_id: str) -> str | None:
    """W3C traceparent value, or None when the ids are missing/malformed.

    Never raises: a bad id must degrade to "no header", never to a failed
    brain call.
    """
    try:
        if not trace_id or not re.fullmatch(r"[0-9a-f]{32}", trace_id):
            return None
        if not re.fullmatch(r"[0-9a-f]{16}", span_id):
            return None
        return f"00-{trace_id}-{span_id}-01"
    except Exception:
        return None


class SttFinalTracker:
    """Latest STT final-transcript event, fed by the session's
    "user_input_transcribed" event.

    livekit-agents 1.8.x AgentSession emits
    UserInputTranscribedEvent(transcript, is_final, language, ...) on every
    STT hypothesis; llm_node take()s the latest *final* one at turn commit to
    measure STT latency. take() clears the slot so a skipped/empty turn can't
    leak a stale timestamp into the next turn.
    """

    def __init__(self) -> None:
        self._timestamp: float | None = None  # perf_counter of the final event
        self._language: str | None = None

    def handle(self, ev) -> None:
        """EventEmitter callback. emit() invokes handlers inline and
        synchronously, so this must not await; it also must never raise."""
        try:
            if getattr(ev, "is_final", False) and (getattr(ev, "transcript", "") or "").strip():
                self._timestamp = time.perf_counter()
                lang = getattr(ev, "language", None)
                # LanguageCode is a str subclass holding the BCP-47 code.
                self._language = str(lang) if lang is not None else None
        except Exception:
            logger.debug("stt final tracker failed", exc_info=True)

    def take(self) -> tuple[float | None, str | None]:
        """Return (timestamp, language) of the latest final transcript and
        clear the slot."""
        ts, lang = self._timestamp, self._language
        self._timestamp, self._language = None, None
        return ts, lang


@dataclasses.dataclass
class TurnTelemetry:
    """Mutable per-turn timing record.

    Created at llm_node commit; tts_node stamps t_tts_first_audio and
    finalizes. The buffer serializes via to_dict() at flush time, so a TTS
    stamp that lands after llm_node finished is still captured. All
    timestamps are time.perf_counter() (monotonic, worker-local); ms
    conversions happen in to_dict(). Fields the worker cannot observe stay
    None (never fabricated).
    """
    room: str
    turn_number: int
    trace_id: str
    span_id: str
    stt_provider: str
    stt_model: str
    tts_provider: str
    tts_model: str
    stt_language: str | None = None
    t_stt_final: float = 0.0        # user_text committed in llm_node
    t_stt_event: float | None = None  # provider final-transcript event
    t_brain_start: float | None = None
    t_brain_first_delta: float | None = None  # Java brain TTFT
    t_tts_first_audio: float | None = None
    t_turn_end: float = 0.0        # llm_node generator completed
    barge_in: bool = False
    aborted: bool = False
    error: str | None = None
    finalized: bool = False

    def finalize(self) -> None:
        self.finalized = True

    @staticmethod
    def _ms(start: float | None, end: float | None) -> float | None:
        if start is None or end is None:
            return None
        return (end - start) * 1000.0

    def to_dict(self) -> dict:
        # Latency definitions (documented, worker's vantage point):
        #   sttLatencyMs:    provider final-transcript event -> turn commit
        #                    (framework turn-detection/queueing delay)
        #   brainTtftMs:     brain call start -> first SSE delta (Java TTFT)
        #   ttsFirstAudioMs: first SSE delta -> first synthesized audio frame
        #   e2eMs:           STT final (event, else commit) -> llm_node end
        #                    (excludes TTS audio playout tail)
        return {
            "room": self.room,
            "turnNumber": self.turn_number,
            "traceId": self.trace_id,
            "sttLatencyMs": self._ms(self.t_stt_event, self.t_stt_final),
            "brainTtftMs": self._ms(self.t_brain_start, self.t_brain_first_delta),
            "ttsFirstAudioMs": self._ms(self.t_brain_first_delta, self.t_tts_first_audio),
            "e2eMs": self._ms(
                self.t_stt_event if self.t_stt_event is not None else self.t_stt_final,
                self.t_turn_end),
            "aborted": self.aborted,
            "bargeIn": self.barge_in,
            "sttLanguage": self.stt_language,
            "sttProvider": self.stt_provider,
            "sttModel": self.stt_model,
            "ttsProvider": self.tts_provider,
            "ttsModel": self.tts_model,
            "error": self.error,
        }


_active_rooms = 0  # process-wide count, for the heartbeat payload


def _inc_active_rooms() -> None:
    global _active_rooms
    _active_rooms += 1


def _dec_active_rooms() -> None:
    global _active_rooms
    _active_rooms = max(0, _active_rooms - 1)


def _active_room_count() -> int:
    return _active_rooms


def _classify_close(ev) -> tuple[str, str | None]:
    """Map a livekit-agents 1.8.x CloseEvent to (outcome, disconnectReason).

    AgentSession emits "close" once during teardown with
    CloseEvent(reason: CloseReason, error). Graceful ends (task completed,
    user hung up) are COMPLETED; anything else (disconnect, shutdown, error)
    is ABORTED. Best-effort: an unrecognised event still yields a record.
    """
    try:
        reason = getattr(ev, "reason", None)
        name = getattr(reason, "value", None) or "unknown"
        if name == "task_completed":
            return "COMPLETED", "task_completed"
        if name == "user_initiated":
            return "COMPLETED", "user_initiated"
        if name == "participant_disconnected":
            return "ABORTED", "participant_disconnected"
        if name == "job_shutdown":
            return "ABORTED", "job_shutdown"
        if name == "error":
            err = getattr(ev, "error", None)
            detail = type(err).__name__ if err is not None else "unknown"
            return "ABORTED", f"error:{detail}"
        if name == "unknown":
            return "ABORTED", "unknown"
        return "ABORTED", f"unknown:{name}"
    except Exception:
        return "ABORTED", "unknown"


class TelemetryBuffer:
    """Bounded in-memory telemetry buffer with background flush.

    record_turn/record_call only append to in-memory lists (bounded,
    drop-oldest with a counter): no I/O, safe to call from the audio
    pipeline. A background task POSTs the accumulated batch every
    flush_seconds; on ANY failure the batch is dropped and ONE throttled
    warning is logged - the audio path is never affected. A second
    background task POSTs the worker heartbeat.

    The flush/heartbeat tasks are independent asyncio tasks: they are
    started in entrypoint and never awaited on the audio path.
    """

    def __init__(self, *, worker_id: str, telemetry_url: str, heartbeat_url: str,
                 secret: str = "", flush_seconds: float = 5.0,
                 heartbeat_seconds: float = 30.0,
                 stt_provider: str = "", tts_provider: str = "",
                 client: httpx.AsyncClient | None = None):
        self._worker_id = worker_id
        self._telemetry_url = telemetry_url
        self._heartbeat_url = heartbeat_url
        self._secret = secret
        self._flush_seconds = flush_seconds
        self._heartbeat_seconds = heartbeat_seconds
        self._stt_provider = stt_provider
        self._tts_provider = tts_provider
        # trust_env=False: this client talks to the (usually local) Java
        # backend, and telemetry construction must never fail because of a
        # malformed proxy env var (httpx parses those eagerly at
        # construction). The brain client keeps default env behavior.
        self._client = client or httpx.AsyncClient(timeout=10.0, trust_env=False)
        self._owns_client = client is None
        self._turns: list[TurnTelemetry] = []
        self._calls: list[dict] = []
        self._dropped_turns = 0
        self._dropped_calls = 0
        self._last_warn_at = 0.0  # monotonic, for warning throttling
        self._flush_task: asyncio.Task | None = None
        self._heartbeat_task: asyncio.Task | None = None

    # -- recording: hot path, sync, bounded, must not raise -----------------

    def record_turn(self, timing: TurnTelemetry) -> None:
        """Append a turn timing record. Drop-oldest past the bound; the
        dropped counter (not per-turn logs) records the loss."""
        if len(self._turns) >= _TELEMETRY_MAX_TURNS:
            self._turns.pop(0)
            self._dropped_turns += 1
        self._turns.append(timing)

    def record_call(self, *, room: str, trace_id: str, outcome: str,
                    barge_in_count: int, disconnect_reason: str | None,
                    started_at: str, ended_at: str) -> None:
        """Append a call-end record (ISO-8601 started_at/ended_at)."""
        if len(self._calls) >= _TELEMETRY_MAX_CALLS:
            self._calls.pop(0)
            self._dropped_calls += 1
        self._calls.append({
            "room": room,
            "traceId": trace_id,
            "outcome": outcome,
            "bargeInCount": barge_in_count,
            "disconnectReason": disconnect_reason,
            "startedAt": started_at,
            "endedAt": ended_at,
        })

    @property
    def pending_turns(self) -> int:
        return len(self._turns)

    @property
    def dropped_turns(self) -> int:
        return self._dropped_turns

    @property
    def dropped_calls(self) -> int:
        return self._dropped_calls

    # -- background loops ----------------------------------------------------

    def start(self) -> None:
        """Spawn the flush + heartbeat tasks on the running loop. Idempotent."""
        if self._flush_task is not None:
            return
        loop = asyncio.get_running_loop()
        self._flush_task = loop.create_task(self._flush_loop(),
                                            name="voxticket-telemetry-flush")
        self._heartbeat_task = loop.create_task(self._heartbeat_loop(),
                                                name="voxticket-telemetry-heartbeat")

    def stop(self) -> None:
        """Cancel the background tasks. Sync-safe; never awaited on audio."""
        for task in (self._flush_task, self._heartbeat_task):
            if task is not None:
                task.cancel()
        self._flush_task = self._heartbeat_task = None

    async def aclose(self) -> None:
        self.stop()
        if self._owns_client:
            await self._client.aclose()

    def _secret_headers(self) -> dict:
        return {"X-VoxTicket-Telemetry-Secret": self._secret}

    def _warn_throttled(self, msg: str, *args) -> None:
        now = time.monotonic()
        if now - self._last_warn_at >= _TELEMETRY_WARN_THROTTLE_S:
            self._last_warn_at = now
            logger.warning(msg, *args)

    async def _flush_loop(self) -> None:
        while True:
            await asyncio.sleep(self._flush_seconds)
            await self._flush_once()

    async def _heartbeat_loop(self) -> None:
        while True:
            await asyncio.sleep(self._heartbeat_seconds)
            await self._heartbeat_once()

    async def _heartbeat_once(self) -> None:
        try:
            await self._client.post(
                self._heartbeat_url,
                json={"workerId": self._worker_id,
                      "sttProvider": self._stt_provider,
                      "ttsProvider": self._tts_provider,
                      "activeRooms": _active_room_count()},
                headers=self._secret_headers(),
            )
        except Exception as exc:
            self._warn_throttled("telemetry heartbeat failed (dropping): %s", exc)

    def _take_flushable(self) -> tuple[list[TurnTelemetry], list[dict]]:
        """Split buffered turns into flush-ready vs still-pending.

        A turn is flush-ready once tts_node finalized it; a turn whose TTS
        never ran (barge-in before any yield) is finalized at llm_node end.
        As a backstop, turns older than _TURN_FINALIZE_TIMEOUT_S are flushed
        anyway so a stuck record can't pin memory past the bound.
        """
        now = time.perf_counter()
        ready, pending = [], []
        for t in self._turns:
            if t.finalized or (now - t.t_turn_end) > _TURN_FINALIZE_TIMEOUT_S:
                ready.append(t)
            else:
                pending.append(t)
        self._turns = pending
        calls, self._calls = self._calls, []
        return ready, calls

    async def _flush_once(self) -> None:
        turns, calls = self._take_flushable()
        if not turns and not calls:
            return
        payload = {
            "workerId": self._worker_id,
            "turns": [t.to_dict() for t in turns],
            "calls": calls,
        }
        try:
            resp = await self._client.post(
                self._telemetry_url, json=payload, headers=self._secret_headers())
            if resp.status_code >= 400:
                self._warn_throttled(
                    "telemetry flush rejected (HTTP %s); batch dropped", resp.status_code)
        except Exception as exc:
            # Drop the batch: telemetry must never back-pressure the worker.
            # No retries - a retry loop on the audio pipeline's behalf could
            # delay real work; the next flush carries newer data instead.
            self._warn_throttled("telemetry flush failed; batch dropped: %s", exc)


# --------------------------------------------------------------------------
# Providers: STT/TTS factories + per-reply voice switching
# --------------------------------------------------------------------------

class VoiceControl:
    """Per-reply TTS voice switching, provider-specific under the hood.

    Each plugin spells its update_options differently (Cartesia: voice=,
    ElevenLabs: voice_id=, Azure: voice=), so the agent talks to this thin
    adapter instead of plugin-specific kwargs. Adding a provider is one
    factory branch plus its voice kwarg name.
    """

    def __init__(self, tts: tts_api.TTS, voice_kwarg: str):
        self._tts = tts
        self._voice_kwarg = voice_kwarg

    def set_voice(self, voice_id: str, language: str) -> None:
        self._tts.update_options(**{self._voice_kwarg: voice_id}, language=language)


@dataclasses.dataclass(frozen=True)
class TtsSetup:
    """Everything the agent needs from the selected TTS provider."""
    tts: tts_api.TTS
    voices: VoiceControl
    voice_en: str
    voice_ur: str


def build_stt(cfg: Config) -> stt_api.STT:
    """Construct the configured STT provider.

    The plugin modules are imported at module top level (livekit-agents
    requires plugin registration on the main thread); this factory only
    decides *which* provider to construct. An unknown provider name is a
    ConfigError, not an ImportError.
    """
    if cfg.stt_provider == "assemblyai":
        return assemblyai.STT(
            api_key=cfg.assemblyai_api_key,
            language_codes=["en", "ur"],  # steer auto-detection to our two languages
            # Bias the decoder toward VoxTicket vocabulary (brand name, OTP,
            # support verbs). Helps most where the model is weakest (Urdu).
            keyterms_prompt=list(cfg.assemblyai_keyterms),
        )
    if cfg.stt_provider == "elevenlabs":
        return elevenlabs.STT(
            model=cfg.elevenlabs_stt_model,
            api_key=cfg.elevenlabs_api_key,
            # Primary language hint plus secondary languages: keeps English
            # primary while still hearing Urdu. include_language_detection
            # reports the language actually heard on each transcript.
            language_code=cfg.elevenlabs_stt_language,
            secondary_languages=list(cfg.elevenlabs_stt_secondary_languages),
            include_language_detection=True,
        )
    raise ConfigError(f"unknown STT provider {cfg.stt_provider!r} (expected one of {list(STT_PROVIDERS)})")


def build_tts(cfg: Config, tokenizer: SentenceTokenizer) -> TtsSetup:
    """Construct the configured TTS provider plus its voice-switch adapter.

    The Urdu-aware sentence tokenizer is passed where the plugin accepts one
    (Cartesia: tokenizer=, ElevenLabs: word_tokenizer=). Azure's plugin has
    no tokenizer hook, so it synthesizes whole segments - correct audio,
    just less streaming granularity on long Urdu replies.
    """
    if cfg.tts_provider == "cartesia":
        tts = cartesia.TTS(
            api_key=cfg.cartesia_api_key,
            model=cfg.cartesia_model,
            language="en",  # per-reply override via VoiceControl
            voice=cfg.cartesia_voice_en,
            tokenizer=tokenizer,
        )
        return TtsSetup(tts, VoiceControl(tts, "voice"), cfg.cartesia_voice_en, cfg.cartesia_voice_ur)
    if cfg.tts_provider == "elevenlabs":
        tts = elevenlabs.TTS(
            api_key=cfg.elevenlabs_api_key,
            model=cfg.elevenlabs_tts_model,
            voice_id=cfg.elevenlabs_voice_en,
            language="en",  # per-reply override via VoiceControl
            word_tokenizer=tokenizer,
        )
        return TtsSetup(tts, VoiceControl(tts, "voice_id"), cfg.elevenlabs_voice_en, cfg.elevenlabs_voice_ur)
    if cfg.tts_provider == "azure":
        tts = azure.TTS(
            speech_key=cfg.azure_speech_key,
            speech_region=cfg.azure_speech_region,
            voice=cfg.azure_voice_en,
            language="en",  # per-reply override via VoiceControl
        )
        return TtsSetup(tts, VoiceControl(tts, "voice"), cfg.azure_voice_en, cfg.azure_voice_ur)
    raise ConfigError(f"unknown TTS provider {cfg.tts_provider!r} (expected one of {list(TTS_PROVIDERS)})")


# --------------------------------------------------------------------------
# Agent: Java brain as the "LLM", TTS voice picked per reply
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
                 tts: tts_api.TTS, voices: VoiceControl,
                 voice_en: str, voice_ur: str, greeting: str,
                 trace_id: str | None = None,
                 telemetry: TelemetryBuffer | None = None,
                 stt_final: SttFinalTracker | None = None,
                 stt_provider: str = "", stt_model: str = "",
                 tts_provider: str = "", tts_model: str = ""):
        # instructions are unused (no LLM provider) but the base class takes them.
        super().__init__(instructions="You are the voice interface for VoxTicket customer support.")
        self._brain = brain
        self._session_id = session_id
        self._tts = tts
        self._voices = voices
        self._voice_en = voice_en
        self._voice_ur = voice_ur
        self._greeting = greeting
        self._next_voice: tuple[str, str] | None = None  # (voice_id, language) stashed by llm_node
        # P3 telemetry (all optional: the agent works identically with none).
        # trace_id is per call (= room), minted in entrypoint.
        self._trace_id = trace_id or uuid.uuid4().hex
        self._telemetry = telemetry
        self._stt_final = stt_final
        self._stt_provider = stt_provider
        self._stt_model = stt_model
        self._tts_provider = tts_provider
        self._tts_model = tts_model
        self._turn_seq = 0  # worker-local turn numbers (Java's turnNumber is separate)
        self._caller_phone_cache: str | None = None  # resolved lazily from the LiveKit room (see below)
        self._barge_in_count = 0
        self._pending_turn: TurnTelemetry | None = None  # stashed for tts_node

    @property
    def barge_in_count(self) -> int:
        """Turns cancelled by caller barge-in during this call."""
        return self._barge_in_count

    def _caller_phone(self) -> str | None:
        """The caller's identity for the Java brain: the E.164 phone the demo
        frontend passed as `identity` to /api/v1/voice/token, minted into the
        LiveKit JWT by our own backend and read back here from the room's
        remote participants. The caller never types it.

        Resolved lazily and cached: the caller may join after the worker.
        Returns None when no E.164 identity is present (anonymous turn, e.g.
        a non-demo caller) - the Java side treats a missing customerPhone
        exactly like today's anonymous voice turns."""
        if self._caller_phone_cache is not None:
            return self._caller_phone_cache
        try:
            participants = self.session.room_io.room.remote_participants
        except Exception:
            return None
        for participant in participants.values():
            identity = (participant.identity or "").strip()
            if re.fullmatch(r"\+\d{7,15}", identity):
                self._caller_phone_cache = identity
                logger.info("resolved caller phone from LiveKit room (identity=%s)", identity)
                return identity
        return None

    def _record_turn_timing(self, timing: TurnTelemetry) -> None:
        """Append the turn record to the buffer. Best-effort: telemetry must
        never raise into the pipeline."""
        try:
            if self._telemetry is not None:
                self._telemetry.record_turn(timing)
        except Exception:
            logger.debug("telemetry record_turn failed", exc_info=True)

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

        Telemetry (P3): every timestamp below is pure time.perf_counter()
        capture - no awaits, no I/O - so measurement can never delay audio.
        The turn timing is recorded (append-only, bounded buffer) in the
        finally block, including on cancellation, so partial/aborted turns
        are visible in voice analytics.
        """
        user_text = self._user_text(chat_ctx).strip()
        if not user_text:
            logger.warning(
                "llm_node invoked with empty user text - skipping turn "
                "(no Java brain call made)"
            )
            return
        # --- telemetry tap points ---
        # t_stt_final: the moment the committed user text becomes available
        # here. The chat_ctx handed to llm_node already contains the final STT
        # transcript; this is the earliest point the worker can act on it.
        # stt_latency_ms (computed at flush): t_stt_final minus the STT
        # provider's final-transcript event time = framework
        # turn-detection/queueing delay. Null when no final event was seen.
        t_stt_final = time.perf_counter()
        stt_event_ts, stt_language = self._stt_final.take() if self._stt_final else (None, None)
        self._turn_seq += 1
        span_id = uuid.uuid4().hex[:16]
        traceparent = _build_traceparent(self._trace_id, span_id)
        timing = TurnTelemetry(
            room=self._session_id,
            turn_number=self._turn_seq,
            trace_id=self._trace_id,
            span_id=span_id,
            stt_provider=self._stt_provider,
            stt_model=self._stt_model,
            tts_provider=self._tts_provider,
            tts_model=self._tts_model,
            stt_language=stt_language,
            t_stt_final=t_stt_final,
            t_stt_event=stt_event_ts,
        )
        # Stashed for tts_node, which stamps the first audio frame and
        # finalizes the record. Cleared there, or here when no TTS will run.
        self._pending_turn = timing
        logger.info(
            "llm_node: turn committed, streaming Java brain (session=%s, %d chars)",
            self._session_id, len(user_text),
        )
        voice_locked = False
        yielded_any = False
        timing.t_brain_start = time.perf_counter()
        try:
            try:
                async for delta in self._brain.turn_stream(
                        self._session_id, user_text, traceparent=traceparent,
                        customer_phone=self._caller_phone()):
                    if timing.t_brain_first_delta is None and delta and delta.strip():
                        # Java brain TTFT: first text out of the SSE stream.
                        timing.t_brain_first_delta = time.perf_counter()
                    if not voice_locked and delta.strip():
                        # First real content decides the turn's TTS voice.
                        self._next_voice = (self._voice_ur, "ur") if is_urdu(delta) else (self._voice_en, "en")
                        voice_locked = True
                        logger.info("llm_node: TTS voice locked voice_id=%s language=%s (first delta %r)",
                                    self._next_voice[0], self._next_voice[1], delta[:60])
                    yielded_any = True
                    yield delta
            except BrainError as exc:
                timing.aborted = True
                timing.error = f"{type(exc).__name__}: {str(exc)[:120]}"
                logger.error("Java brain turn failed: %s", exc)
                if not yielded_any:
                    # Nothing spoken yet: the apology can still take the turn.
                    # After speech started, partial audio can't be unsaid.
                    yield (
                        "Sorry, I'm having trouble reaching the service right now. "
                        "Please try again in a moment."
                    )
        except asyncio.CancelledError:
            # Barge-in (or session teardown): LiveKit cancels this generator.
            # The partial turn is recorded below; re-raise so the pipeline
            # tears the turn down exactly as before.
            timing.barge_in = True
            timing.aborted = True
            self._barge_in_count += 1
            logger.info("llm_node: turn cancelled (barge-in), partial turn recorded")
            raise
        finally:
            timing.t_turn_end = time.perf_counter()
            if not voice_locked:
                self._next_voice = (self._voice_en, "en")
            if not yielded_any:
                # No text was ever produced: tts_node will not run for this
                # turn, so there is nothing left to stamp - finalize now.
                # (When chunks were yielded, tts_node finalizes instead.)
                self._pending_turn = None
                timing.finalize()
            self._record_turn_timing(timing)
            # Drain any late STT final events that arrived during this turn:
            # they belong to the just-finished turn, and without this a
            # duplicate/correction final would leak its stale timestamp into
            # the NEXT turn's sttLatencyMs.
            if self._stt_final is not None:
                self._stt_final.take()
            logger.info("llm_node: brain stream ended (yielded_any=%s)", yielded_any)

    async def tts_node(self, text, model_settings):
        """Apply the voice stashed by llm_node, then run the default node.

        If the non-English voice fails to synthesize (e.g. CARTESIA_VOICE_UR
        is not a voice that supports Urdu, Cartesia returns zero audio
        frames), fall back to the English voice once instead of leaving the
        caller in silence. The warning names the culprit so the
        misconfiguration still gets fixed.

        Telemetry (P3): stamps the first synthesized audio frame on the turn
        timing stashed by llm_node and finalizes the record when synthesis
        for this turn completes. Pass-through only - no awaits added.
        """
        voice_id, language = self._next_voice or (self._voice_en, "en")
        self._next_voice = None
        timing = self._pending_turn
        self._pending_turn = None
        self._voices.set_voice(voice_id, language)
        logger.info("tts_node: synthesizing with voice_id=%s language=%s", voice_id, language)
        try:
            try:
                async for frame in self._timed_frames(
                        Agent.default.tts_node(self, text, model_settings), timing):
                    yield frame
            except Exception as exc:
                if language != "ur":
                    raise
                logger.warning(
                    "Non-English TTS voice %r failed (%s); falling back to English voice",
                    voice_id, exc,
                )
                self._voices.set_voice(self._voice_en, "en")
                async for frame in self._timed_frames(
                        Agent.default.tts_node(self, text, model_settings), timing):
                    yield frame
        finally:
            if timing is not None:
                timing.finalize()

    @staticmethod
    async def _timed_frames(frames: AsyncIterator, timing: TurnTelemetry | None) -> AsyncIterator:
        """Yield audio frames through, stamping the first one on the turn.

        First-audio latency is measured from the first brain text delta to
        the first synthesized audio frame (TTS pipeline latency; the speaker
        playout tail is out of the worker's view).
        """
        async for frame in frames:
            if timing is not None and timing.t_tts_first_audio is None:
                timing.t_tts_first_audio = time.perf_counter()
            yield frame


def _stt_model_name(cfg: Config) -> str:
    """STT model identifier for telemetry facts."""
    if cfg.stt_provider == "assemblyai":
        # livekit-plugins-assemblyai default (verified against 1.8.x).
        return "universal-3-6-pro"
    if cfg.stt_provider == "elevenlabs":
        return cfg.elevenlabs_stt_model
    return cfg.stt_provider


def _tts_model_name(cfg: Config) -> str:
    """TTS model identifier for telemetry facts."""
    if cfg.tts_provider == "cartesia":
        return cfg.cartesia_model
    if cfg.tts_provider == "elevenlabs":
        return cfg.elevenlabs_tts_model
    # livekit-plugins-azure has no model id; voice names are the stable id.
    return "azure-neural"


# --------------------------------------------------------------------------
# Entrypoint
# --------------------------------------------------------------------------

server = AgentServer()


@server.rtc_session()
async def entrypoint(ctx: agents.JobContext):
    cfg = load_config()
    logging.basicConfig(level=cfg.log_level.upper())
    logger.info("providers: stt=%s tts=%s", cfg.stt_provider, cfg.tts_provider)

    # P3 voice telemetry. One trace_id per call (= room); the background
    # buffer flushes batches to Java. All best-effort: telemetry setup must
    # never fail the call, and the flush/heartbeat tasks are never awaited
    # on the audio pipeline.
    trace_id = uuid.uuid4().hex
    worker_id = cfg.worker_id or f"{socket.gethostname()}-{os.getpid()}"
    # Telemetry is best-effort: if even buffer construction fails, the call
    # continues with telemetry=None (the agent treats that as "disabled").
    telemetry: TelemetryBuffer | None
    try:
        telemetry = TelemetryBuffer(
            worker_id=worker_id,
            telemetry_url=cfg.voxticket_telemetry_url,
            heartbeat_url=cfg.voxticket_heartbeat_url,
            secret=cfg.voice_telemetry_secret,
            flush_seconds=cfg.telemetry_flush_seconds,
            heartbeat_seconds=cfg.heartbeat_seconds,
            stt_provider=cfg.stt_provider,
            tts_provider=cfg.tts_provider,
        )
        telemetry.start()
    except Exception:
        logger.warning("voice telemetry unavailable; call continues without it",
                       exc_info=True)
        telemetry = None
    _inc_active_rooms()
    room_name = ctx.room.name
    call_started_at = datetime.now(timezone.utc).isoformat()

    stt_final = SttFinalTracker()

    brain = VoxTicketBrain(cfg.voxticket_turn_url, cfg.brain_timeout_s,
                           stream_url=cfg.voxticket_turn_stream_url)
    # Urdu-aware sentence chunking: the stock tokenizers don't split on
    # ۔/؟, which would delay all Urdu audio until the full reply arrives.
    # Passed to providers that accept a tokenizer hook (Cartesia,
    # ElevenLabs); Azure synthesizes whole segments instead.
    tts_setup = build_tts(cfg, UrduAwareSentenceTokenizer())
    agent = VoxTicketAgent(
        brain=brain,
        session_id=ctx.room.name,  # room name == VoxTicket conversation session
        tts=tts_setup.tts,
        voices=tts_setup.voices,
        voice_en=tts_setup.voice_en,
        voice_ur=tts_setup.voice_ur,
        greeting=cfg.greeting,
        trace_id=trace_id,
        telemetry=telemetry,
        stt_final=stt_final,
        stt_provider=cfg.stt_provider,
        stt_model=_stt_model_name(cfg),
        tts_provider=cfg.tts_provider,
        tts_model=_tts_model_name(cfg),
    )

    session = AgentSession(
        stt=build_stt(cfg),
        # Speech-onset threshold from config (VAD_ACTIVATION_THRESHOLD,
        # default 0.4): aligns with AssemblyAI's internal VAD default so the
        # two VADs agree on speech onset and turn detection and STT
        # endpointing don't fight each other. See the config field comment
        # for the tuning methodology.
        vad=silero.VAD.load(activation_threshold=cfg.vad_activation_threshold),
        tts=tts_setup.tts,
        # Placeholder LLM: non-None is REQUIRED, otherwise the pipeline
        # silently skips reply generation ("skip response if no llm is set").
        # The real brain is VoxTicketAgent.llm_node -> Java backend.
        llm=_PlaceholderLLM(),
        # Default audio turn detector stays on. Preemptive generation MUST stay
        # off: speculative calls would execute real, stateful Java turns twice.
        # Interruption mode is pinned to adaptive (not the production default
        # of VAD-only): the ML detector distinguishes real interruptions from
        # backchannels ("okay") and noise, and falls back to VAD gracefully
        # if the inference service is unreachable.
        turn_handling={
            "preemptive_generation": {"enabled": False},
            "interruption": {"mode": "adaptive"},
        },
        # AEC warmup: caller audio ignored this long after the agent starts
        # speaking (echo cancellation settling). Tunable via env; lower is
        # more responsive, higher is safer on echoey audio.
        aec_warmup_duration=cfg.aec_warmup_duration_s,
        # If VAD hears the caller but STT never produces a transcript, speak
        # a reprompt instead of leaving dead air. The handler is below.
        transcription_timeout=cfg.transcription_timeout_s or None,
    )

    # STT final-transcript events feed the per-turn STT latency measurement.
    # (Sync handler: EventEmitter.emit() invokes callbacks inline, so this
    # must never await.)
    session.on("user_input_transcribed", stt_final.handle)

    def _on_transcription_timeout(ev) -> None:
        # Sync by necessity (EventEmitter.emit invokes handlers inline).
        # VAD heard speech but STT produced nothing: the caller gets a
        # spoken reprompt, not silence. session.say() is synchronous and
        # keeps this out of the Java conversation (no phantom turn); it is
        # interruptible, so a caller who is still talking can cut it off.
        try:
            logger.warning("STT transcription timeout; speaking reprompt")
            session.say(cfg.transcription_reprompt, add_to_chat_ctx=False)
        except Exception:
            logger.warning("transcription reprompt failed", exc_info=True)

    session.on("user_transcription_timeout", _on_transcription_timeout)

    def _on_session_close(ev) -> None:
        # Sync by necessity (see above). Records the call-end event and stops
        # the telemetry tasks; never raises into the session teardown.
        try:
            if telemetry is not None:
                outcome, reason = _classify_close(ev)
                telemetry.record_call(
                    room=room_name,
                    trace_id=trace_id,
                    outcome=outcome,
                    barge_in_count=agent.barge_in_count,
                    disconnect_reason=reason,
                    started_at=call_started_at,
                    ended_at=datetime.now(timezone.utc).isoformat(),
                )
                logger.info("voice call ended room=%s outcome=%s reason=%s barge_ins=%d",
                            room_name, outcome, reason, agent.barge_in_count)
        except Exception:
            logger.warning("telemetry: failed to record call end", exc_info=True)
        finally:
            _dec_active_rooms()
            if telemetry is not None:
                telemetry.stop()

    # livekit-agents 1.8.x: AgentSession emits "close" exactly once during
    # session teardown (see AgentSession._close: after activity teardown,
    # before room IO closes) with CloseEvent(reason: CloseReason, error).
    # This is the framework's own session-end signal - no polling of room
    # state and no rtc.Room event subscription needed.
    session.on("close", _on_session_close)

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
