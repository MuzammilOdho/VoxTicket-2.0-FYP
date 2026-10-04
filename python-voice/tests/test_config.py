"""Config loading: fail fast and name the missing variable."""

import pytest

from livekit.agents import llm

from agent import ConfigError, _PlaceholderLLM, load_config

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
    assert cfg.cartesia_model == "sonic-3.6"
    assert cfg.assemblyai_keyterms == ("VoxTicket", "OTP", "order", "refund",
                                       "delivery", "tracking", "payment",
                                       "verification code")
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
               GREETING="Hi", LOG_LEVEL="DEBUG",
               ASSEMBLYAI_KEYTERMS="alpha, beta ,,")
    cfg = load_config(env)
    assert cfg.cartesia_model == "sonic-turbo"
    assert cfg.brain_timeout_s == 10.0
    assert cfg.greeting == "Hi"
    assert cfg.log_level == "DEBUG"
    assert cfg.assemblyai_keyterms == ("alpha", "beta")


def test_placeholder_llm_is_a_real_non_none_llm_instance():
    """The pipeline skips reply generation entirely when session llm is None,
    so the placeholder must be a genuine llm.LLM instance (not None)."""
    ph = _PlaceholderLLM()
    assert isinstance(ph, llm.LLM)
    assert not isinstance(ph, llm.RealtimeModel)


def test_placeholder_llm_chat_is_never_meant_to_be_called():
    """Documents the contract: llm_node is fully overridden, so if the
    placeholder's chat() is ever invoked something regressed."""
    ph = _PlaceholderLLM()
    with pytest.raises(RuntimeError, match="fully overridden"):
        ph.chat(chat_ctx=llm.ChatContext())
