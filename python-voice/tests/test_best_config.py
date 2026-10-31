"""Best voice config 2026-10-31: defaults must be the audited ideal values.

These tests pin the config so a later edit cannot silently revert to the
old defaults (Silero 0.4, AssemblyAI balanced, plugin 100 ms silences,
stacked LiveKit endpointing) without a failing test saying so.
"""

import pytest

from agent import (
    _NETWORK_ENDPOINTING,
    ConfigError,
    build_stt,
    load_config,
)

_BASE_ENV = {
    "LIVEKIT_URL": "wss://example.livekit.cloud",
    "LIVEKIT_API_KEY": "test-key",
    "LIVEKIT_API_SECRET": "test-secret",
    "ASSEMBLYAI_API_KEY": "test-assemblyai-key",
    "CARTESIA_API_KEY": "test-cartesia-key",
    "CARTESIA_VOICE_EN": "voice-en",
    "CARTESIA_VOICE_UR": "voice-ur",
}


def test_best_config_defaults():
    cfg = load_config(dict(_BASE_ENV))
    assert cfg.vad_activation_threshold == 0.3
    assert cfg.assemblyai_model == "universal-3-6-pro"
    assert cfg.assemblyai_mode == "min_latency"
    assert cfg.assemblyai_vad_threshold == 0.3
    assert cfg.assemblyai_max_turn_silence_ms == 1000


def test_assemblyai_mode_override_and_rejection():
    cfg = load_config({**_BASE_ENV, "ASSEMBLYAI_MODE": "balanced"})
    assert cfg.assemblyai_mode == "balanced"
    with pytest.raises(ConfigError):
        load_config({**_BASE_ENV, "ASSEMBLYAI_MODE": "fast"})


def test_network_endpointing_min_delay_is_zero_on_every_tier():
    # In STT turn-detection mode LiveKit's min_delay adds to AssemblyAI's
    # own endpoint signal, so any non-zero value is pure stacked wait.
    assert _NETWORK_ENDPOINTING == {
        "full": (0.0, 3.0),
        "low": (0.0, 4.0),
        "survival": (0.0, 5.0),
    }


def test_build_stt_passes_best_config_to_assemblyai():
    cfg = load_config(dict(_BASE_ENV))
    stt = build_stt(cfg)
    assert stt.model == "universal-3-6-pro"
    assert stt._opts.mode == "min_latency"
    assert stt._opts.vad_threshold == 0.3
    assert stt._opts.max_turn_silence == 1000
    # min_turn_silence stays unset so the mode's tuning governs it.
    from livekit.agents.types import NOT_GIVEN

    assert stt._opts.min_turn_silence is NOT_GIVEN
