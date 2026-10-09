"""Urdu-script detection drives per-reply TTS voice picking."""

from agent import is_urdu


def test_english_is_not_urdu():
    assert not is_urdu("Hello, how can I help you today?")


def test_urdu_script_is_urdu():
    assert is_urdu("السلام علیکم، آپ کی کیا مدد کر سکتا ہوں؟")


def test_mixed_script_counts_as_urdu():
    assert is_urdu("Your order نمبر 12345 ہے")


def test_empty_and_none_are_not_urdu():
    assert not is_urdu("")
    assert not is_urdu(None)


def test_digits_and_punctuation_alone_are_not_urdu():
    assert not is_urdu("12345?!")
