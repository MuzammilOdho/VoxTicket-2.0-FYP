"""Config loading: fail fast and name the missing variable."""

import pytest

from agent import ConfigError, load_config

FULL_ENV = {
    "LIVEKIT_URL": "ws://localhost:7880",
    "LIVEKIT_API_KEY": "devkey",
    "LIVEKIT_API_SECRET": "secret",
    "ASSEMBLYAI_API_KEY": "aai-key",
    "CARTESIA_API_KEY": "cart-key",
    "CARTESIA_VOICE_EN": "voice-en-id",
    "CARTESIA_VOICE_UR": "voice-ur-id",
}


def test_full_env_loads():
    cfg = load_config(dict(FULL_ENV))
    assert cfg.livekit_url == "ws://localhost:7880"
    assert cfg.cartesia_voice_ur == "voice-ur-id"
    # defaults kick in
    assert cfg.cartesia_model == "sonic-3"
    assert cfg.voxticket_turn_url == "http://localhost:8080/api/v1/voice/turn"
    assert cfg.brain_timeout_s == 25.0


def test_each_missing_required_var_is_named():
    for missing in FULL_ENV:
        env = {k: v for k, v in FULL_ENV.items() if k != missing}
        with pytest.raises(ConfigError) as exc_info:
            load_config(env)
        assert missing in str(exc_info.value), f"{missing} not named in error"


def test_blank_value_counts_as_missing():
    env = dict(FULL_ENV, CARTESIA_API_KEY="   ")
    with pytest.raises(ConfigError) as exc_info:
        load_config(env)
    assert "CARTESIA_API_KEY" in str(exc_info.value)


def test_optional_overrides_are_honored():
    env = dict(FULL_ENV, CARTESIA_MODEL="sonic-turbo", BRAIN_TIMEOUT_S="10",
               GREETING="Hi", LOG_LEVEL="DEBUG")
    cfg = load_config(env)
    assert cfg.cartesia_model == "sonic-turbo"
    assert cfg.brain_timeout_s == 10.0
    assert cfg.greeting == "Hi"
    assert cfg.log_level == "DEBUG"
