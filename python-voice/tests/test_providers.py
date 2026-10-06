"""Provider selection: every model is configurable and replaceable.

Covers the STT/TTS factories (assemblyai/elevenlabs STT; cartesia/elevenlabs/
azure TTS), the fail-fast gated validation (only the selected provider's keys
are required), and the VoiceControl adapter that maps per-reply voice
switching onto each plugin's own update_options kwargs.
"""

import dataclasses

import pytest

from livekit.agents import stt as stt_api
from livekit.agents import tts as tts_api

from agent import (
    ConfigError,
    VoiceControl,
    build_stt,
    build_tts,
    load_config,
)


BASE_ENV = {
    "LIVEKIT_URL": "ws://localhost:7880",
    "LIVEKIT_API_KEY": "devkey",
    "LIVEKIT_API_SECRET": "secret",
    "ASSEMBLYAI_API_KEY": "aai-key",
    "CARTESIA_API_KEY": "cart-key",
    "CARTESIA_VOICE_EN": "cart-en",
    "CARTESIA_VOICE_UR": "cart-ur",
}


class RecordingTTS:
    def __init__(self):
        self.calls = []

    def update_options(self, **kwargs):
        self.calls.append(kwargs)


class FakeTokenizer:
    def tokenize(self, text, *, language=None):
        return [text]

    def stream(self, *, language=None):
        raise NotImplementedError


# --------------------------------------------------------------------------
# Factory construction (fake keys, no network)
# --------------------------------------------------------------------------

def test_build_stt_assemblyai():
    cfg = load_config(dict(BASE_ENV))
    stt = build_stt(cfg)
    assert isinstance(stt, stt_api.STT)


def test_build_stt_elevenlabs_scribe():
    env = dict(BASE_ENV, STT_PROVIDER="elevenlabs", ELEVENLABS_API_KEY="el-key")
    # AssemblyAI key must NOT be required when elevenlabs STT is selected.
    del env["ASSEMBLYAI_API_KEY"]
    cfg = load_config(env)
    assert cfg.stt_provider == "elevenlabs"
    stt = build_stt(cfg)
    assert isinstance(stt, stt_api.STT)


def test_build_tts_cartesia():
    cfg = load_config(dict(BASE_ENV))
    setup = build_tts(cfg, FakeTokenizer())
    assert isinstance(setup.tts, tts_api.TTS)
    assert (setup.voice_en, setup.voice_ur) == ("cart-en", "cart-ur")
    assert isinstance(setup.voices, VoiceControl)


def test_build_tts_elevenlabs():
    env = dict(
        BASE_ENV,
        TTS_PROVIDER="elevenlabs",
        ELEVENLABS_API_KEY="el-key",
        ELEVENLABS_VOICE_EN="el-en",
        ELEVENLABS_VOICE_UR="el-ur",
    )
    cfg = load_config(env)
    setup = build_tts(cfg, FakeTokenizer())
    assert isinstance(setup.tts, tts_api.TTS)
    assert (setup.voice_en, setup.voice_ur) == ("el-en", "el-ur")


def test_build_tts_azure():
    env = dict(
        BASE_ENV,
        TTS_PROVIDER="azure",
        AZURE_SPEECH_KEY="az-key",
        AZURE_SPEECH_REGION="westeurope",
    )
    # Cartesia keys must NOT be required when azure TTS is selected.
    del env["CARTESIA_API_KEY"]
    del env["CARTESIA_VOICE_EN"]
    del env["CARTESIA_VOICE_UR"]
    cfg = load_config(env)
    setup = build_tts(cfg, FakeTokenizer())
    assert isinstance(setup.tts, tts_api.TTS)
    assert (setup.voice_en, setup.voice_ur) == (
        "en-US-AvaMultilingualNeural",
        "ur-PK-AsadNeural",
    )


# --------------------------------------------------------------------------
# Fail-fast gated validation
# --------------------------------------------------------------------------

def test_unknown_stt_provider_rejected():
    with pytest.raises(ConfigError, match="STT_PROVIDER"):
        load_config(dict(BASE_ENV, STT_PROVIDER="bogus"))


def test_unknown_tts_provider_rejected():
    with pytest.raises(ConfigError, match="TTS_PROVIDER"):
        load_config(dict(BASE_ENV, TTS_PROVIDER="bogus"))


def test_unknown_provider_rejected_by_factory_too():
    cfg = dataclasses.replace(load_config(dict(BASE_ENV)), stt_provider="bogus")
    with pytest.raises(ConfigError, match="unknown STT provider"):
        build_stt(cfg)
    cfg = dataclasses.replace(load_config(dict(BASE_ENV)), tts_provider="bogus")
    with pytest.raises(ConfigError, match="unknown TTS provider"):
        build_tts(cfg, FakeTokenizer())


def test_elevenlabs_stt_names_missing_key():
    env = dict(BASE_ENV, STT_PROVIDER="elevenlabs")
    del env["ASSEMBLYAI_API_KEY"]
    with pytest.raises(ConfigError) as exc_info:
        load_config(env)
    assert "ELEVENLABS_API_KEY" in str(exc_info.value)


def test_elevenlabs_tts_names_missing_keys():
    env = dict(BASE_ENV, TTS_PROVIDER="elevenlabs", ELEVENLABS_API_KEY="el-key")
    with pytest.raises(ConfigError) as exc_info:
        load_config(env)
    msg = str(exc_info.value)
    assert "ELEVENLABS_VOICE_EN" in msg
    assert "ELEVENLABS_VOICE_UR" in msg


def test_azure_tts_names_missing_key_and_region():
    env = dict(BASE_ENV, TTS_PROVIDER="azure")
    with pytest.raises(ConfigError) as exc_info:
        load_config(env)
    msg = str(exc_info.value)
    assert "AZURE_SPEECH_KEY" in msg
    assert "AZURE_SPEECH_REGION" in msg


def test_unselected_providers_keys_are_not_required():
    # elevenlabs for both: neither AssemblyAI nor Cartesia keys needed.
    env = {
        "LIVEKIT_URL": "ws://localhost:7880",
        "LIVEKIT_API_KEY": "devkey",
        "LIVEKIT_API_SECRET": "secret",
        "STT_PROVIDER": "elevenlabs",
        "TTS_PROVIDER": "elevenlabs",
        "ELEVENLABS_API_KEY": "el-key",
        "ELEVENLABS_VOICE_EN": "el-en",
        "ELEVENLABS_VOICE_UR": "el-ur",
    }
    cfg = load_config(env)
    assert cfg.stt_provider == "elevenlabs"
    assert cfg.tts_provider == "elevenlabs"


# --------------------------------------------------------------------------
# VoiceControl adapter
# --------------------------------------------------------------------------

def test_voice_control_maps_cartesia_kwargs():
    tts = RecordingTTS()
    VoiceControl(tts, "voice").set_voice("v-1", "ur")
    assert tts.calls == [{"voice": "v-1", "language": "ur"}]


def test_voice_control_maps_elevenlabs_kwargs():
    tts = RecordingTTS()
    VoiceControl(tts, "voice_id").set_voice("v-2", "en")
    assert tts.calls == [{"voice_id": "v-2", "language": "en"}]


def test_built_setups_switch_voice_without_error():
    # End to end through the real plugin objects (fake keys): the adapter's
    # kwargs must match what each plugin's update_options actually accepts.
    cartesia_setup = build_tts(load_config(dict(BASE_ENV)), FakeTokenizer())
    cartesia_setup.voices.set_voice("cart-ur", "ur")

    el_env = dict(
        BASE_ENV,
        TTS_PROVIDER="elevenlabs",
        ELEVENLABS_API_KEY="el-key",
        ELEVENLABS_VOICE_EN="el-en",
        ELEVENLABS_VOICE_UR="el-ur",
    )
    el_setup = build_tts(load_config(el_env), FakeTokenizer())
    el_setup.voices.set_voice("el-ur", "ur")

    az_env = dict(
        BASE_ENV,
        TTS_PROVIDER="azure",
        AZURE_SPEECH_KEY="az-key",
        AZURE_SPEECH_REGION="westeurope",
    )
    az_setup = build_tts(load_config(az_env), FakeTokenizer())
    az_setup.voices.set_voice("ur-PK-AsadNeural", "ur")
