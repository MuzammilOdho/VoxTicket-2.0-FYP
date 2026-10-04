"""UrduAwareSentenceTokenizer: Urdu ۔/؟ must split like Latin boundaries."""

import pytest

from agent import UrduAwareSentenceTokenizer, _split_sentences


def test_split_sentences_breaks_on_urdu_full_stop():
    parts = [s for s, _, _ in _split_sentences("جی ہاں۔ کیا آپ کو مدد چاہیے؟")]
    assert parts == ["جی ہاں۔", "کیا آپ کو مدد چاہیے؟"]


def test_split_sentences_keeps_latin_behavior():
    parts = [s for s, _, _ in _split_sentences("Hello there. How can I help?")]
    assert parts == ["Hello there.", "How can I help?"]


def test_split_sentences_mixed_script():
    parts = [s for s, _, _ in _split_sentences("Sure. جی بالکل۔ Done.")]
    assert parts == ["Sure.", "جی بالکل۔", "Done."]


def test_tokenizer_tokenize():
    tok = UrduAwareSentenceTokenizer()
    assert tok.tokenize("پہلا جملہ۔ دوسرا جملہ۔") == ["پہلا جملہ۔", "دوسرا جملہ۔"]


@pytest.mark.asyncio
async def test_tokenizer_stream_emits_short_first_sentence_promptly():
    # A short complete sentence must not wait for a long buffer: as soon as
    # the next chunk proves it complete, it goes out (first-audio latency).
    tok = UrduAwareSentenceTokenizer()
    stream = tok.stream()
    # A complete sentence (>= min_token_len) must not wait for a long buffer:
    # as soon as the next chunk proves it complete, it goes out (first audio).
    stream.push_text("جی ہاں، بالکل۔ کیا")
    got = []

    async def collect():
        async for ev in stream:
            got.append(ev.token)

    import asyncio
    task = asyncio.create_task(collect())
    await asyncio.sleep(0.2)
    assert got == ["جی ہاں، بالکل۔"], f"complete sentence should have been emitted, got {got}"
    stream.push_text(" آپ کو مدد چاہیے؟")
    stream.end_input()
    await task
    await stream.aclose()
    assert "".join(got).endswith("؟")
