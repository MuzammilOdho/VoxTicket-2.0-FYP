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
from typing import AsyncIterator, Callable

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

from livekit import agents, rtc
from livekit import api as livekit_api
from livekit.agents import Agent, AgentServer, AgentSession, llm
from livekit.agents import stt as stt_api
from livekit.agents import tts as tts_api
from livekit.agents.tokenize import SentenceStream, SentenceTokenizer, token_stream
from livekit.agents.voice import room_io
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
    # In-band filler spoken while the Java brain is thinking: when no brain
    # content arrives within filler_after_s, llm_node yields the filler as
    # the first chunk of the turn's own text stream (interruptible, never a
    # separate say(), excluded from TTS voice-locking and TTFT telemetry).
    # Picked by the STT final's detected language ("ur" prefix -> Urdu).
    # 0 disables the filler.
    filler_after_s: float = 1.5
    filler_text_en: str = "One moment, let me check that for you. "
    filler_text_ur: str = "ایک لمحہ، میں دیکھ رہا ہوں۔ "
    # Mid-stream stall watchdog (P1.4): when brain deltas flow and then stop,
    # the caller gets half a sentence + silence. If no brain delta arrives
    # within stall_after_s of the previous one, llm_node yields the repair
    # phrase below, closes the stream, and ends the turn (telemetry marks
    # stalled=true). The timer only arms after the first brain delta -
    # pre-first-delta slowness is the filler's domain. 0 disables.
    stall_after_s: float = 8.0
    stall_repair: str = (
        "Sorry, I lost my train of thought — could you say that again?"
    )
    # --- P3.3: telephony (SIP) ---
    # LiveKit SIP trunk id for PSTN calls (empty = disabled; the WebRTC demo
    # path is then byte-for-byte unchanged). The dispatch rule name is
    # informational: it is logged at startup and carried in the telephony
    # metadata; the rule itself lives in the LiveKit dashboard / API - see
    # docs/SIP_SETUP.md.
    sip_trunk_id: str = ""
    sip_dispatch_rule: str = ""
    # --- P3.2: call recording ---
    # Room-composite egress to S3, started per call when enabled. The egress
    # destination URL is config; storage credentials are env-only
    # (VOICE_RECORDING_S3_* - see .env.example). Never hardcode them.
    recording_enabled: bool = False
    recording_egress_url: str = ""


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

    def opt_bool(key: str, default: bool) -> bool:
        raw = (env.get(key) or "").strip().lower()
        if not raw:
            return default
        if raw in ("1", "true", "yes", "on"):
            return True
        if raw in ("0", "false", "no", "off"):
            return False
        raise ConfigError(f"{key} must be a boolean (true/false), got {raw!r}")

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

    cfg = Config(
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
        filler_after_s=opt_float("VOICE_FILLER_AFTER_S", 1.5),
        filler_text_en=opt(
            "VOICE_FILLER_TEXT_EN",
            "One moment, let me check that for you. ",
        ),
        filler_text_ur=opt(
            "VOICE_FILLER_TEXT_UR",
            "ایک لمحہ، میں دیکھ رہا ہوں۔ ",
        ),
        stall_after_s=opt_float("VOICE_STALL_AFTER_S", 8.0),
        stall_repair=opt(
            "VOICE_STALL_REPAIR",
            "Sorry, I lost my train of thought — could you say that again?",
        ),
        sip_trunk_id=opt("SIP_TRUNK_ID", ""),
        sip_dispatch_rule=opt("SIP_DISPATCH_RULE", ""),
        recording_enabled=opt_bool("VOICE_RECORDING_ENABLED", False),
        recording_egress_url=opt("VOICE_RECORDING_EGRESS_URL", ""),
    )

    # P3.2: recording is fail-fast - a bad destination or missing storage
    # credentials must break startup, not the first real customer call.
    if cfg.recording_enabled:
        _parse_s3_egress_url(cfg.recording_egress_url)  # raises ConfigError
        missing = [k for k in ("VOICE_RECORDING_S3_REGION",
                               "VOICE_RECORDING_S3_ACCESS_KEY",
                               "VOICE_RECORDING_S3_SECRET_KEY")
                   if not (env.get(k) or "").strip()]
        if missing:
            raise ConfigError(
                "Missing required environment variables for "
                "VOICE_RECORDING_ENABLED: " + ", ".join(missing)
                + ". See worker/.env.example."
            )

    return cfg


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


class BrainSessionBusyError(BrainError):
    """The Java session stayed locked behind a previous turn through all
    429 retries: the new utterance never reached the brain and is lost
    unless the caller repeats it. Distinct from a generic BrainError so the
    caller hears an honest "please repeat that" instead of a service
    apology."""


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

    @staticmethod
    def _headers(traceparent: str | None,
                 idempotency_key: str | None) -> dict[str, str] | None:
        """Headers shared by turn() and turn_stream(). traceparent is a
        best-effort W3C correlation header; Idempotency-Key identifies the
        LOGICAL turn (one key per turn, not per POST attempt) so the Java
        side can dedupe a retried turn that already committed. A
        missing/invalid value never fails the call (headers=None)."""
        headers: dict[str, str] = {}
        if traceparent:
            headers["traceparent"] = traceparent
        if idempotency_key:
            headers["Idempotency-Key"] = idempotency_key
        return headers or None

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
                   customer_phone: str | None = None,
                   idempotency_key: str | None = None) -> str:
        """Blocking turn. traceparent is a best-effort W3C correlation header;
        a missing/invalid value never fails the call (headers=None).
        customer_phone is the caller's backend-minted identity (E.164), read
        back from the LiveKit room; omitted when unknown (anonymous turn).
        idempotency_key identifies the logical turn for Java-side dedupe."""
        headers = self._headers(traceparent, idempotency_key)
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
                          customer_phone: str | None = None,
                          idempotency_key: str | None = None,
                          on_429: Callable[[int], None] | None = None,
                          ) -> AsyncIterator[str]:
        """Yield reply deltas from the SSE stream endpoint.

        Falls back to the blocking endpoint (yielded as one chunk) when the
        Java side predates the stream endpoint (404/405). Retries up to
        _BRAIN_429_MAX_ATTEMPTS times on HTTP 429 (session busy: the previous
        barge-in-aborted turn is still unwinding on the Java side); when the
        retries are exhausted it raises BrainSessionBusyError (a BrainError
        subclass) so the caller can speak an honest "please repeat that".
        Raises BrainError on other failures; cancelling the consumer
        (barge-in) closes the stream, which lets the Java side abort the
        turn and release its session lock.

        traceparent is forwarded on both the stream and the fallback call so
        the Java side can correlate the whole turn. customer_phone (the
        caller's backend-minted E.164 identity) is forwarded the same way;
        omitted when unknown. idempotency_key identifies the logical turn
        for Java-side dedupe. on_429, when given, is called with the attempt
        number on every 429 retry (telemetry hook; never raises into the
        stream loop).
        """
        url = self._stream_url
        headers = self._headers(traceparent, idempotency_key)
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
                                                customer_phone=customer_phone,
                                                idempotency_key=idempotency_key)
                        return
                    if resp.status_code == 429 and attempt < _BRAIN_429_MAX_ATTEMPTS:
                        # The previous turn still holds the Java session lock.
                        # Retry the turn instead of failing: the abort is
                        # landing and the lock is about to release.
                        await resp.aread()  # release the connection
                        logger.info("brain session busy (HTTP 429); retrying turn in %.1fs (attempt %d of %d)",
                                    _BRAIN_429_RETRY_DELAY_S, attempt + 1, _BRAIN_429_MAX_ATTEMPTS)
                        if on_429 is not None:
                            try:
                                on_429(attempt + 1)
                            except Exception:
                                logger.debug("on_429 hook raised; ignored", exc_info=True)
                        await asyncio.sleep(_BRAIN_429_RETRY_DELAY_S)
                        continue
                    if resp.status_code == 429:
                        body = await resp.aread()
                        raise BrainSessionBusyError(
                            f"brain session stayed busy (HTTP 429) after {attempt} attempts: {body[:200]!r}")
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
    # P0.5: failure-mode observability. brain_429_attempts counts the POST
    # attempts for this turn (1 = first try succeeded, no retry);
    # lost_utterance is True when 429s were exhausted and the caller's text
    # never reached the Java brain (it must be repeated).
    brain_429_attempts: int = 1
    lost_utterance: bool = False
    # P1.4: brain deltas stopped mid-stream and the stall watchdog ended the
    # turn with the repair phrase.
    stalled: bool = False

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
            "spanId": self.span_id,
            "sttLatencyMs": self._ms(self.t_stt_event, self.t_stt_final),
            "brainTtftMs": self._ms(self.t_brain_start, self.t_brain_first_delta),
            "ttsFirstAudioMs": self._ms(self.t_brain_first_delta, self.t_tts_first_audio),
            "e2eMs": self._ms(
                self.t_stt_event if self.t_stt_event is not None else self.t_stt_final,
                self.t_turn_end),
            "aborted": self.aborted,
            "bargeIn": self.barge_in,
            "brain429Attempts": self.brain_429_attempts,
            "lostUtterance": self.lost_utterance,
            "stalled": self.stalled,
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


# --------------------------------------------------------------------------
# P3.2: OTP redaction. Callers dictate OTP codes over voice, so any
# free-text path that could carry a transcript (telemetry payloads, future
# logging) must mask OTP-like spans first. The masking primitive is
# redact_otp(); redact_telemetry_payload() is the choke point applied to
# every flush payload in TelemetryBuffer._flush_once.
#
# By design the worker never logs transcript text today (llm_node logs
# character counts, not content); if a text-carrying log is ever added it
# must pass through redact_otp().
# --------------------------------------------------------------------------

_OTP_RE = re.compile(r"(?<!\d)\d{4,8}(?!\d)")


def redact_otp(text: str | None) -> str | None:
    """Mask 4-8 digit OTP-like spans in free text.

    Every run of 4-8 digits (not part of a longer digit run) becomes "****".
    3-digit and 9+-digit runs are left alone: masking longer runs would
    hide order/tracking numbers the support flow legitimately needs, and
    3-digit runs are too common (CVV-adjacent but also years, counts) to
    blanket-mask without a transcript-aware classifier.
    Never raises; returns the input unchanged on any error.
    """
    try:
        if not text:
            return text
        return _OTP_RE.sub("****", text)
    except Exception:
        return text


def redact_telemetry_payload(payload: dict) -> dict:
    """Return a copy of a telemetry payload with OTP-like spans masked.

    Applied to the full flush payload in _flush_once so that any free-text
    field - present or future - leaves the worker OTP-masked. Only string
    values under *unknown* keys are redacted: keys in _MACHINE_KEYS hold
    machine-generated values (IDs, ISO timestamps, provider names, fixed
    codes) that are never caller speech and must not be mangled (a hex
    trace id or an ISO year like "2026" would otherwise match the OTP
    pattern). A future free-text field uses a new key, so it is redacted
    by default. Never raises.
    """
    try:
        return _redact_value(payload, None)
    except Exception:
        return payload


# Payload keys whose string values are machine-generated and therefore
# never caller speech: worker/session identifiers, W3C trace ids, ISO-8601
# timestamps, provider/model names, language codes, and fixed status
# codes. Everything else (unknown keys, e.g. a future transcript note)
# is treated as potentially caller-dictated and redacted.
_MACHINE_KEYS = frozenset({
    "workerId", "room", "turnNumber", "traceId", "spanId",
    "sttLatencyMs", "brainTtftMs", "ttsFirstAudioMs", "e2eMs",
    "aborted", "bargeIn", "brain429Attempts", "lostUtterance", "stalled",
    "sttLanguage", "sttProvider", "sttModel", "ttsProvider", "ttsModel",
    "error", "outcome", "bargeInCount", "disconnectReason",
    "startedAt", "endedAt",
})


def _redact_value(value, key):
    if isinstance(value, str):
        if key in _MACHINE_KEYS:
            return value
        return redact_otp(value)
    if isinstance(value, dict):
        return {k: _redact_value(v, k) for k, v in value.items()}
    if isinstance(value, (list, tuple)):
        return [_redact_value(v, key) for v in value]
    return value


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
        # P3.2: mask OTP-like spans before anything leaves the worker.
        payload = redact_telemetry_payload(payload)
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


# --------------------------------------------------------------------------
# Adaptive audio: network tiers from the demo frontend
# --------------------------------------------------------------------------
#
# The demo publishes its mic at an adaptive tier ('full' | 'low' |
# 'survival', see frontend/src/demo/adaptiveAudio.ts) and broadcasts the
# tier on the LiveKit data channel (topic "voxticket.network", JSON schema
# mirrored in adaptiveAudio.ts encodeTierBroadcast). The worker applies the
# matching turn-taking patience per tier via the public
# AgentSession.update_options runtime API.
#
# Why patience, not codec: on a jittery link the caller's speech arrives in
# bursts with small network pauses. The default endpointing (0.5s) mistakes
# a network pause for end-of-turn and either cuts the caller off or commits
# a turn whose tail never arrived ("misses words"). Waiting longer costs a
# beat of responsiveness and buys complete sentences.

_NETWORK_TOPIC = "voxticket.network"
_NETWORK_BROADCAST_VERSION = 1
_NETWORK_TIERS = ("full", "low", "survival")

# (min_delay, max_delay) seconds per tier: how long after the caller stops
# speaking the worker waits before committing the turn. 'full' is the
# framework default; worse tiers wait longer.
_NETWORK_ENDPOINTING: dict[str, tuple[float, float]] = {
    "full": (0.5, 3.0),
    "low": (0.8, 4.0),
    "survival": (1.2, 5.0),
}


# Turn-state broadcasts for the demo UI's "thinking" indicator. Published
# by the worker on the 'voxticket.turn' data topic (reliable, same
# data-channel mechanism as 'voxticket.network'): the frontend shows
# "Thinking…" while the state is "thinking" and no agent audio has started,
# instead of a frozen "Listening…". States: thinking (turn committed, brain
# pending), first_audio (first brain delta; TTS about to start),
# done / aborted (turn finished).
_TURN_TOPIC = "voxticket.turn"
_TURN_TOPIC_VERSION = 1
_TURN_STATES = ("thinking", "first_audio", "done", "aborted")


def decode_tier_broadcast(raw: bytes) -> dict | None:
    """Parse a 'voxticket.network' data-channel payload.

    Returns the payload dict, or None when it is malformed, a version we
    don't understand, or names an unknown tier. Never raises: a bad
    broadcast must degrade to "keep current tier", never to a failed call.
    """
    try:
        payload = json.loads(bytes(raw).decode("utf-8"))
    except Exception:
        return None
    if not isinstance(payload, dict):
        return None
    if payload.get("v") != _NETWORK_BROADCAST_VERSION:
        return None
    if payload.get("tier") not in _NETWORK_TIERS:
        return None
    return payload


# --------------------------------------------------------------------------
# P3.3: E.164 caller identity. On SIP/PSTN calls the LiveKit SIP service
# mints the participant identity from the caller's phone number; on the
# WebRTC demo path the backend mints it into the JWT. Either way the worker
# must verify before trusting it: strip formatting, ensure a leading +,
# and accept only +\d{7,15}. Anything else is logged once and treated as
# unknown (the turn continues anonymously - identity is never required to
# take the call).
# --------------------------------------------------------------------------

def normalize_e164(raw: str | None) -> str | None:
    """Normalize a candidate caller identity to E.164, or None.

    Strips whitespace and dashes, prepends + when missing, and validates
    the E.164 shape (+ followed by 7-15 digits). Returns None for anything
    else - with a clear warning - and never raises. The raw value is
    truncated in the log so a malformed identity can't dump an unbounded
    string.
    """
    try:
        if not raw or not raw.strip():
            return None
        cleaned = re.sub(r"[\s\-()]", "", raw.strip())
        if cleaned and not cleaned.startswith("+"):
            cleaned = "+" + cleaned
        if re.fullmatch(r"\+\d{7,15}", cleaned):
            return cleaned
        logger.warning("rejecting non-E.164 caller identity %r; continuing as unknown",
                       raw[:32])
        return None
    except Exception:
        logger.debug("E.164 normalization failed", exc_info=True)
        return None


class VoxTicketAgent(Agent):
    def __init__(self, *, brain: VoxTicketBrain, session_id: str,
                 tts: tts_api.TTS, voices: VoiceControl,
                 voice_en: str, voice_ur: str, greeting: str,
                 trace_id: str | None = None,
                 telemetry: TelemetryBuffer | None = None,
                 stt_final: SttFinalTracker | None = None,
                 stt_provider: str = "", stt_model: str = "",
                 tts_provider: str = "", tts_model: str = "",
                 filler_after_s: float = 1.5,
                 filler_text_en: str = "One moment, let me check that for you. ",
                 filler_text_ur: str = "ایک لمحہ، میں دیکھ رہا ہوں۔ ",
                 stall_after_s: float = 8.0,
                 stall_repair: str = (
                     "Sorry, I lost my train of thought — could you say that again?"
                 ),
                 outbound_reason: str | None = None):
        # instructions are unused (no LLM provider) but the base class takes them.
        super().__init__(instructions="You are the voice interface for VoxTicket customer support.")
        self._brain = brain
        self._session_id = session_id
        self._tts = tts
        self._voices = voices
        self._voice_en = voice_en
        self._voice_ur = voice_ur
        self._greeting = greeting
        # P4.1: outbound dial-out. When set, on_enter waits for the callee to
        # join before speaking (never greet an empty room), and the greeting
        # carries the outbound reason context instead of the default.
        self._outbound_reason = outbound_reason
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
        # Last network tier applied from the demo's data-channel broadcast
        # (None until the first broadcast arrives; the frontend starts at
        # 'low' and heartbeats it, so this converges within seconds).
        self._network_tier: str | None = None
        # In-band filler while the Java brain thinks (P0.4): yielded as the
        # first chunk of the turn's own text stream when no brain content
        # arrives within filler_after_s. Never a separate say(); excluded
        # from TTS voice-locking and TTFT telemetry.
        self._filler_after_s = filler_after_s
        self._filler_text_en = filler_text_en
        self._filler_text_ur = filler_text_ur
        # Mid-stream stall watchdog (P1.4): armed after the first brain
        # delta, reset on every delta; fires the repair phrase when deltas
        # stop mid-sentence.
        self._stall_after_s = stall_after_s
        self._stall_repair = stall_repair

    @property
    def barge_in_count(self) -> int:
        """Turns cancelled by caller barge-in during this call."""
        return self._barge_in_count

    @property
    def network_tier(self) -> str | None:
        """Last network tier applied from the demo frontend (None if none yet)."""
        return self._network_tier

    def apply_network_tier(self, tier: str | None) -> bool:
        """Apply the demo frontend's audio tier (see _NETWORK_ENDPOINTING).

        Unknown tiers and repeats are ignored (returns False). Applies the
        tier's endpointing patience via the public AgentSession.update_options
        runtime API; takes effect on subsequent turns. Never raises: a
        failed tier application keeps the current patience, it never breaks
        the call.
        """
        if tier not in _NETWORK_TIERS or tier == self._network_tier:
            return False
        min_delay, max_delay = _NETWORK_ENDPOINTING[tier]
        try:
            self.session.update_options(
                endpointing_opts={"min_delay": min_delay, "max_delay": max_delay}
            )
        except Exception:
            logger.warning("network tier %r: failed to update endpointing", tier,
                           exc_info=True)
            return False
        self._network_tier = tier
        logger.info(
            "network tier -> %s (endpointing min_delay=%.1fs max_delay=%.1fs)",
            tier, min_delay, max_delay,
        )
        return True

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
            # P3.3: verify/normalize before trusting - a SIP participant
            # identity is caller-supplied (Twilio passes the PSTN number
            # through), and garbage must degrade to "unknown", not to a
            # malformed customerPhone.
            normalized = normalize_e164(participant.identity)
            if normalized is not None:
                self._caller_phone_cache = normalized
                logger.info("resolved caller phone from LiveKit room (identity=%s)", normalized)
                return normalized
        return None

    def _record_turn_timing(self, timing: TurnTelemetry) -> None:
        """Append the turn record to the buffer. Best-effort: telemetry must
        never raise into the pipeline."""
        try:
            if self._telemetry is not None:
                self._telemetry.record_turn(timing)
        except Exception:
            logger.debug("telemetry record_turn failed", exc_info=True)

    def _publish_turn_state(self, turn_number: int, state: str) -> None:
        """Broadcast the turn state for the demo UI's "thinking" indicator.

        Publishes {"v":1,"turn":N,"state":...} on the 'voxticket.turn' data
        topic (reliable). Best-effort: never raises into the audio pipeline;
        a UI that never listens is unaffected."""
        if state not in _TURN_STATES:
            return
        try:
            payload = json.dumps(
                {"v": _TURN_TOPIC_VERSION, "turn": turn_number, "state": state}
            ).encode("utf-8")
            self.session.room_io.room.local_participant.publish_data(
                payload, reliable=True, topic=_TURN_TOPIC)
        except Exception:
            logger.debug("turn-state broadcast failed", exc_info=True)

    def _filler_text(self, stt_language: str | None) -> str:
        """The in-band filler for this turn, picked by the STT final's
        detected language ("ur" prefix -> Urdu). Empty string disables."""
        if (stt_language or "").lower().startswith("ur"):
            return self._filler_text_ur
        return self._filler_text_en

    async def on_enter(self) -> None:
        # Greet directly: this must NOT go through llm_node, or the Java
        # brain would record a phantom turn before the caller says anything.
        if self._outbound_reason:
            # P4.1: outbound call - the callee may still be joining when the
            # session starts. Greeting an empty room means nobody hears it,
            # so wait (bounded) for the first remote participant.
            await self._wait_for_callee()
        self.session.say(self._greeting)

    async def _wait_for_callee(self, timeout_s: float = 45.0) -> None:
        """P4.1: wait for the dialed party to join the room.

        Bounded poll on remote_participants; on timeout the greeting plays
        anyway (voicemail / late-join edge) rather than leaving dead air.
        Never raises.
        """
        try:
            room = self.session.room_io.room
            deadline = time.monotonic() + timeout_s
            while not room.remote_participants and time.monotonic() < deadline:
                await asyncio.sleep(0.5)
        except Exception:
            logger.debug("outbound callee wait failed; greeting anyway", exc_info=True)

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
        yielded_any = False        # any chunk at all (filler or brain): tts_node will run
        brain_yielded_any = False  # brain content: drives apology + voice-lock source + TTFT
        filler_yielded = False
        timing.t_brain_start = time.perf_counter()
        # P0.6: one idempotency key per LOGICAL turn (not per POST attempt),
        # so the Java side can dedupe a retried turn that already committed.
        idempotency_key = uuid.uuid4().hex
        self._publish_turn_state(self._turn_seq, "thinking")
        stream = self._brain.turn_stream(
            self._session_id, user_text, traceparent=traceparent,
            customer_phone=self._caller_phone(),
            idempotency_key=idempotency_key,
            on_429=lambda attempt: setattr(timing, "brain_429_attempts", attempt),
        )
        # P0.3: hold the generator so cancellation can close it
        # deterministically (aclose) instead of leaving it to the event
        # loop's asyncgen GC finalizer.
        next_chunk = asyncio.ensure_future(stream.__anext__())
        # P0.4: the filler timer races the first brain content. It fires at
        # most once; brain content arriving first leaves it unheard.
        filler_timer: asyncio.Future | None = None
        if self._filler_after_s and self._filler_after_s > 0:
            filler_timer = asyncio.ensure_future(asyncio.sleep(self._filler_after_s))
        # P1.4: mid-stream stall watchdog. Armed after the first brain delta
        # and reset on every delta; fires when deltas stop mid-sentence.
        stall_timer: asyncio.Future | None = None

        async def _abort_stream(chunk_task: asyncio.Task, brain_stream) -> None:
            """Deterministically tear down the brain stream (P0.3/P1.4).

            Cancel the in-flight __anext__ and WAIT for the generator to
            unwind (its `async with` closes the HTTP stream), then aclose()
            as a no-op safeguard. Never raises.

            NOTE: aclose() must run AFTER the pending __anext__ finishes -
            calling it while the generator is already running raises
            RuntimeError, and merely cancelling without awaiting leaves the
            unwind to a later event-loop tick (nondeterministic).
            """
            chunk_task.cancel()
            try:
                await chunk_task
            except asyncio.CancelledError:
                pass  # expected: the in-flight chunk was aborted
            except Exception:
                pass  # generator already finished (StopAsyncIteration/BrainError)
            try:
                await brain_stream.aclose()
            except Exception:
                logger.debug("stream aclose failed", exc_info=True)
        try:
            try:
                while True:
                    wait_for = {next_chunk}
                    if filler_timer is not None:
                        wait_for.add(filler_timer)
                    if stall_timer is not None:
                        wait_for.add(stall_timer)
                    done, _ = await asyncio.wait(wait_for, return_when=asyncio.FIRST_COMPLETED)
                    if filler_timer is not None and filler_timer in done:
                        filler_timer = None
                        if not brain_yielded_any and not filler_yielded:
                            filler_text = self._filler_text(stt_language)
                            if filler_text and filler_text.strip():
                                filler_yielded = True
                                yielded_any = True
                                logger.info("llm_node: no brain content in %.1fs; yielding filler",
                                            self._filler_after_s)
                                # Exactly one separating space: env values are
                                # stripped by load_config, and brain deltas
                                # must not glue onto the filler's last word.
                                yield filler_text.rstrip() + " "
                    if next_chunk in done:
                        try:
                            delta = next_chunk.result()
                        except StopAsyncIteration:
                            break
                        if timing.t_brain_first_delta is None and delta and delta.strip():
                            # Java brain TTFT: first text out of the SSE stream.
                            # (The filler never counts: it is worker speech, not
                            # brain output.)
                            timing.t_brain_first_delta = time.perf_counter()
                            self._publish_turn_state(self._turn_seq, "first_audio")
                        if not voice_locked and delta.strip():
                            # First real content decides the turn's TTS voice.
                            # (The filler never locks the voice: it is always
                            # superseded by the brain's language choice.)
                            self._next_voice = (self._voice_ur, "ur") if is_urdu(delta) else (self._voice_en, "en")
                            voice_locked = True
                            logger.info("llm_node: TTS voice locked voice_id=%s language=%s (first delta %r)",
                                        self._next_voice[0], self._next_voice[1], delta[:60])
                        brain_yielded_any = True
                        yielded_any = True
                        # P1.4: (re)arm the stall watchdog on every brain delta.
                        if self._stall_after_s and self._stall_after_s > 0:
                            if stall_timer is not None and not stall_timer.done():
                                stall_timer.cancel()
                            stall_timer = asyncio.ensure_future(asyncio.sleep(self._stall_after_s))
                        yield delta
                        next_chunk = asyncio.ensure_future(stream.__anext__())
                        continue
                    if stall_timer is not None and stall_timer in done:
                        stall_timer = None
                        # P1.4: deltas stopped mid-stream. Say so honestly and
                        # end the turn - the partial reply plus the repair
                        # phrase is the turn's output. The stream is closed
                        # deterministically, like the cancel path.
                        timing.stalled = True
                        timing.aborted = True
                        logger.warning("llm_node: brain stalled mid-stream; yielding repair")
                        await _abort_stream(next_chunk, stream)
                        yield self._stall_repair
                        break
            except BrainSessionBusyError as exc:
                # P0.5: the 429s were exhausted - the utterance never reached
                # Java. Say so honestly and ask for a repeat; never the
                # generic service apology.
                timing.aborted = True
                timing.lost_utterance = True
                timing.error = f"{type(exc).__name__}: {str(exc)[:120]}"
                logger.error("Java brain session stayed busy; utterance lost: %s", exc)
                if not brain_yielded_any:
                    yield (
                        "I missed that while finishing your previous request. "
                        "Could you please say it again?"
                    )
            except BrainError as exc:
                timing.aborted = True
                timing.error = f"{type(exc).__name__}: {str(exc)[:120]}"
                logger.error("Java brain turn failed: %s", exc)
                if not brain_yielded_any:
                    # Nothing spoken yet: the apology can still take the turn.
                    # After speech started, partial audio can't be unsaid.
                    # (A filler already spoken does not suppress this: the
                    # caller heard "one moment…" and deserves the failure.)
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
            # P0.3: deterministic teardown - abort the in-flight chunk, wait
            # for the stream generator to unwind, then close it explicitly.
            if filler_timer is not None:
                filler_timer.cancel()
            if stall_timer is not None:
                stall_timer.cancel()
            await _abort_stream(next_chunk, stream)
            raise
        finally:
            if filler_timer is not None and not filler_timer.done():
                filler_timer.cancel()
            if stall_timer is not None and not stall_timer.done():
                stall_timer.cancel()
            self._publish_turn_state(
                self._turn_seq, "aborted" if (timing.barge_in or timing.aborted) else "done")
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
            logger.info("llm_node: brain stream ended (yielded_any=%s, brain_yielded_any=%s)",
                        yielded_any, brain_yielded_any)

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
# P3.3: telephony (SIP) + P3.2: call recording. Both are strictly opt-in:
# with empty SIP_TRUNK_ID and VOICE_RECORDING_ENABLED unset, these
# functions do nothing and the WebRTC demo path is untouched.
# --------------------------------------------------------------------------

def telephony_metadata(cfg: Config) -> dict:
    """Telephony metadata to attach to the room when SIP is configured.

    Returns {} when SIP_TRUNK_ID is empty (disabled): fully inert - no
    logging beyond debug, no room metadata, no behavioral difference
    anywhere. When a trunk is configured, returns the metadata the agent
    publishes on its participant so the demo/admin UI can tell a PSTN
    call from a WebRTC one.
    """
    trunk = (cfg.sip_trunk_id or "").strip()
    if not trunk:
        return {}
    return {
        "telephony": "sip",
        "sip_trunk_id": trunk,
        "sip_dispatch_rule": (cfg.sip_dispatch_rule or "").strip(),
    }


# --------------------------------------------------------------------------
# P4.1: outbound dial-out context. outbound.py mints a room whose metadata
# carries {"voxticket_outbound": {"reason", "to", "greeting"}}; the entrypoint
# reads it here so the agent greets with the reason context and waits for
# the callee before speaking. Returns None for anything else (inbound
# WebRTC/SIP calls, garbage metadata) - never raises.
# --------------------------------------------------------------------------

OUTBOUND_METADATA_KEY = "voxticket_outbound"


def parse_outbound_context(metadata: str | None) -> dict | None:
    try:
        if not metadata:
            return None
        payload = json.loads(metadata)
        if not isinstance(payload, dict):
            return None
        ctx = payload.get(OUTBOUND_METADATA_KEY)
        if not isinstance(ctx, dict):
            return None
        reason = str(ctx.get("reason") or "").strip()
        greeting = str(ctx.get("greeting") or "").strip()
        if not reason or not greeting:
            return None
        return {"reason": reason, "to": str(ctx.get("to") or ""), "greeting": greeting}
    except Exception:
        logger.debug("outbound context parse failed", exc_info=True)
        return None


def _parse_s3_egress_url(url: str) -> tuple[str, str]:
    """Parse VOICE_RECORDING_EGRESS_URL (s3://bucket/optional/prefix).

    Returns (bucket, prefix). Raises ConfigError naming the variable when
    the URL is missing or not an s3:// URL - recording is fail-fast at
    startup, never mid-call.
    """
    dest = (url or "").strip()
    if not dest.lower().startswith("s3://"):
        raise ConfigError(
            "VOICE_RECORDING_EGRESS_URL must be an s3:// URL "
            f"(e.g. s3://my-bucket/voxticket-recordings), got {dest!r}"
        )
    rest = dest[5:].strip("/")
    bucket, _, prefix = rest.partition("/")
    if not bucket:
        raise ConfigError(
            f"VOICE_RECORDING_EGRESS_URL has no bucket: {dest!r}"
        )
    return bucket, prefix


async def _start_call_recording(cfg: Config, room_name: str) -> None:
    """Start LiveKit room-composite egress for this call (P3.2).

    Called once per call from entrypoint, after session.start. Inert when
    VOICE_RECORDING_ENABLED is false (the default): no API call, no log.
    Best-effort otherwise: any failure is logged and the call continues
    unrecorded - recording must never fail a customer call. The recording
    is audio-only (voice calls carry no video) and lands in the S3
    destination from VOICE_RECORDING_EGRESS_URL; storage credentials come
    from VOICE_RECORDING_S3_* env vars (validated fail-fast in
    load_config) and are never hardcoded or logged.
    """
    if not cfg.recording_enabled:
        return
    try:
        bucket, prefix = _parse_s3_egress_url(cfg.recording_egress_url)
        region = (os.environ.get("VOICE_RECORDING_S3_REGION") or "").strip()
        access_key = (os.environ.get("VOICE_RECORDING_S3_ACCESS_KEY") or "").strip()
        secret_key = (os.environ.get("VOICE_RECORDING_S3_SECRET_KEY") or "").strip()
        # Positional (url, api key, api secret) matching LiveKitAPI's
        # signature order.
        lkapi = livekit_api.LiveKitAPI(
            cfg.livekit_url, cfg.livekit_api_key, cfg.livekit_api_secret
        )
        try:
            s3 = livekit_api.S3Upload(
                bucket=bucket, region=region,
                access_key=access_key, secret=secret_key,
            )
            filepath = f"{prefix}/voxticket-{{room_name}}-{{time}}.ogg".lstrip("/")
            output = livekit_api.EncodedFileOutput(filepath=filepath, s3=s3)
            request = livekit_api.RoomCompositeEgressRequest(
                room_name=room_name, audio_only=True, file_outputs=[output],
            )
            await lkapi.egress.start_room_composite_egress(request)
            logger.info("call recording started: room=%s dest=s3://%s/%s",
                        room_name, bucket, filepath)
        finally:
            await lkapi.aclose()
    except Exception:
        logger.warning("call recording failed to start; continuing unrecorded",
                       exc_info=True)


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

    # P3.3: SIP telephony. Empty trunk id = disabled: nothing is logged here
    # and every line below behaves exactly as the WebRTC demo path. When a
    # trunk is configured, the trunk is logged at startup (per call) and
    # the telephony metadata is published on the agent's participant after
    # session.start so the demo/admin UI can tell a PSTN call from WebRTC.
    # Live verification needs a Twilio account - see docs/SIP_SETUP.md.
    telephony = telephony_metadata(cfg)
    if telephony:
        logger.info("SIP telephony enabled: trunk_id=%s dispatch_rule=%s",
                    cfg.sip_trunk_id,
                    cfg.sip_dispatch_rule or "(none configured)")

    stt_final = SttFinalTracker()

    brain = VoxTicketBrain(cfg.voxticket_turn_url, cfg.brain_timeout_s,
                           stream_url=cfg.voxticket_turn_stream_url)
    # P4.1: outbound dial-out context from the room metadata (minted by
    # outbound.py). Overrides the greeting and makes the agent wait for the
    # callee before speaking; inbound calls are unaffected (None here).
    outbound_ctx = parse_outbound_context(getattr(ctx.room, "metadata", None))
    greeting = outbound_ctx["greeting"] if outbound_ctx else cfg.greeting
    outbound_reason = outbound_ctx["reason"] if outbound_ctx else None
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
        greeting=greeting,
        outbound_reason=outbound_reason,
        trace_id=trace_id,
        telemetry=telemetry,
        stt_final=stt_final,
        stt_provider=cfg.stt_provider,
        stt_model=_stt_model_name(cfg),
        tts_provider=cfg.tts_provider,
        tts_model=_tts_model_name(cfg),
        filler_after_s=cfg.filler_after_s,
        filler_text_en=cfg.filler_text_en,
        filler_text_ur=cfg.filler_text_ur,
        stall_after_s=cfg.stall_after_s,
        stall_repair=cfg.stall_repair,
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

    def _on_network_tier(data_packet: rtc.DataPacket) -> None:
        # Sync by necessity (EventEmitter.emit invokes handlers inline).
        # The demo frontend broadcasts its adaptive audio tier on the
        # 'voxticket.network' data topic (tier + measured loss/jitter/RTT);
        # the worker answers with matching endpointing patience. Unknown
        # topics and malformed payloads are ignored.
        try:
            if data_packet.topic != _NETWORK_TOPIC:
                return
            payload = decode_tier_broadcast(data_packet.data)
            if payload is None:
                return
            agent.apply_network_tier(payload.get("tier"))
        except Exception:
            logger.debug("ignoring malformed network-tier broadcast", exc_info=True)

    ctx.room.on("data_received", _on_network_tier)

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
    # The agent's own voice gets the same loss protection as the caller's
    # mic: RED (redundant audio data) rebuilds a lost packet from its
    # neighbour with no retransmit round-trip — the direct fix for "voice
    # breaks and misses words" on lossy links. DTX keeps silence free.
    # Always on: inaudible overhead on a fast link, call saving on a slow
    # one. (The demo's mic side sets the same flags in
    # frontend/src/demo/adaptiveAudio.ts.)
    # NOTE: room_output_options belongs on start(), not on the AgentSession
    # constructor — the constructor rejects it (TypeError).
    await session.start(
        room=ctx.room,
        agent=agent,
        room_output_options=room_io.RoomOutputOptions(
            audio_publish_options=rtc.TrackPublishOptions(
                red=True, dtx=True, source=rtc.TrackSource.SOURCE_MICROPHONE,
            ),
        ),
    )

    # P3.3: the agent's room options carry the telephony metadata - best
    # effort, SIP calls only. A WebRTC call never reaches this branch.
    if telephony:
        try:
            await session.room_io.room.local_participant.set_metadata(
                json.dumps(telephony))
            logger.debug("telephony metadata published on room %s", room_name)
        except Exception:
            logger.warning("telephony metadata publish failed; continuing",
                           exc_info=True)

    # P3.2: call recording via LiveKit room-composite egress. The hook is
    # inert unless VOICE_RECORDING_ENABLED=true, and best-effort: a failed
    # start logs and the call continues unrecorded.
    await _start_call_recording(cfg, room_name)


if __name__ == "__main__":
    # Validate before the worker starts so a misconfigured environment fails
    # here with the friendly ConfigError (naming the missing variable)
    # instead of deep inside the framework's startup.
    load_config()
    agents.cli.run_app(server)
