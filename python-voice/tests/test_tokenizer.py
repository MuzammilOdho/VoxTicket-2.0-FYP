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


def test_split_sentences_breaks_on_latin_comma():
    # Latency Tier A: a comma-heavy reply must not wait for the next full
    # stop before its first clause can reach TTS.
    parts = [s for s, _, _ in _split_sentences("Hello, world. How are you?")]
    assert parts == ["Hello,", "world.", "How are you?"]


def test_split_sentences_breaks_on_urdu_comma():
    parts = [s for s, _, _ in _split_sentences("جی ہاں، بالکل۔")]
    assert parts == ["جی ہاں،", "بالکل۔"]


@pytest.mark.asyncio
async def test_tokenizer_stream_emits_short_first_sentence_promptly():
    # A short complete clause must not wait for a long buffer: as soon as
    # the next chunk proves it complete, it goes out (first-audio latency).
    # Latency Tier A: ، is now a boundary, so the first clause goes out
    # on its own instead of waiting for the full sentence to finish.
    tok = UrduAwareSentenceTokenizer()
    stream = tok.stream()
    stream.push_text("جی ہاں، بالکل۔ کیا")
    got = []

    async def collect():
        async for ev in stream:
            got.append(ev.token)

    import asyncio
    task = asyncio.create_task(collect())
    await asyncio.sleep(0.2)
    assert got == ["جی ہاں،", "بالکل۔"], f"complete clauses should have been emitted, got {got}"
    stream.push_text(" آپ کو مدد چاہیے؟")
    stream.end_input()
    await task
    await stream.aclose()
    assert "".join(got).endswith("؟")


@pytest.mark.asyncio
async def test_tokenizer_stream_emits_six_char_clause_but_holds_tiny_fragment():
    # Latency Tier A hold is 6 chars, not 10: "Hello," (6 chars) emits
    # promptly, while a 3-char fragment like "Hi," is still held until
    # more context proves it complete (abbreviations / decimals stay safe).
    tok = UrduAwareSentenceTokenizer()
    stream = tok.stream()
    stream.push_text("Hello, world")
    got = []

    async def collect():
        async for ev in stream:
            got.append(ev.token)

    import asyncio
    task = asyncio.create_task(collect())
    await asyncio.sleep(0.2)
    assert got == ["Hello,"], f"6-char clause should have been emitted, got {got}"
    stream.end_input()
    await task
    await stream.aclose()

    tok2 = UrduAwareSentenceTokenizer()
    stream2 = tok2.stream()
    stream2.push_text("Hi, there")
    got2 = []

    async def collect2():
        async for ev in stream2:
            got2.append(ev.token)

    task2 = asyncio.create_task(collect2())
    await asyncio.sleep(0.2)
    assert got2 == [], f"tiny fragment must still be held, got {got2}"
    stream2.end_input()
    await task2
    await stream2.aclose()
    assert "".join(got2) == "Hi, there"
