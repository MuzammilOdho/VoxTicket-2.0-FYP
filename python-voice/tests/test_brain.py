"""VoxTicketBrain: one turn in, one reply text out; every failure is a BrainError."""

import httpx
import pytest

from agent import BrainError, VoxTicketBrain
import json

def make_brain(handler) -> VoxTicketBrain:
    transport = httpx.MockTransport(handler)
    client = httpx.AsyncClient(transport=transport, timeout=5.0)
    return VoxTicketBrain("http://java:8080/api/v1/voice/turn", client=client)


@pytest.mark.asyncio
async def test_turn_posts_session_and_message_and_returns_text():
    seen = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["json"] = json.loads(request.content)
        return httpx.Response(200, json={
            "sessionId": "room-1", "text": "Hello there", "turnNumber": 3,
            "requiresVerification": False, "requiresConfirmation": False,
            "identityAssurance": "ANONYMOUS",
        })

    brain = make_brain(handler)
    reply = await brain.turn("room-1", "hi")
    assert reply == "Hello there"
    assert seen["json"] == {"sessionId": "room-1", "message": "hi"}


@pytest.mark.asyncio
async def test_non_200_is_a_brain_error():
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(500, text="boom")

    with pytest.raises(BrainError, match="HTTP 500"):
        await make_brain(handler).turn("room-1", "hi")


@pytest.mark.asyncio
async def test_empty_reply_text_is_a_brain_error():
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"sessionId": "room-1", "text": "   "})

    with pytest.raises(BrainError, match="empty reply"):
        await make_brain(handler).turn("room-1", "hi")


@pytest.mark.asyncio
async def test_unreachable_brain_is_a_brain_error():
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("connection refused")

    with pytest.raises(BrainError, match="could not reach"):
        await make_brain(handler).turn("room-1", "hi")


def sse_handler(deltas, done=True):
    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.path == "/api/v1/voice/turn/stream"
        seen["json"] = json.loads(request.content)
        body = "".join(f'data: {json.dumps({"delta": d})}\n\n' for d in deltas)
        if done:
            body += f'data: {json.dumps({"done": True})}\n\n'
        return httpx.Response(200, text=body, headers={"content-type": "text/event-stream"})
    seen = {}
    return handler, seen


@pytest.mark.asyncio
async def test_turn_stream_yields_deltas_in_order():
    handler, seen = sse_handler(["Hello, ", "world."])
    brain = make_brain(handler)
    chunks = [c async for c in brain.turn_stream("room-1", "hi")]
    assert chunks == ["Hello, ", "world."]
    assert seen["json"] == {"sessionId": "room-1", "message": "hi"}


@pytest.mark.asyncio
async def test_turn_stream_stops_at_done_and_ignores_noise():
    def handler(request: httpx.Request) -> httpx.Response:
        body = 'data: {"delta": "a"}\n\n:keep-alive\n\ndata: not-json\n\ndata: {"delta": "b"}\n\ndata: {"done": true}\n\ndata: {"delta": "c"}\n\n'
        return httpx.Response(200, text=body)

    chunks = [c async for c in make_brain(handler).turn_stream("room-1", "hi")]
    assert chunks == ["a", "b"]


@pytest.mark.asyncio
async def test_turn_stream_falls_back_to_blocking_on_404():
    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path.endswith("/turn/stream"):
            return httpx.Response(404, text="not found")
        return httpx.Response(200, json={"sessionId": "room-1", "text": "blocking reply"})

    chunks = [c async for c in make_brain(handler).turn_stream("room-1", "hi")]
    assert chunks == ["blocking reply"]


@pytest.mark.asyncio
async def test_turn_stream_non_200_is_a_brain_error():
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(500, text="boom")

    with pytest.raises(BrainError, match="HTTP 500"):
        [c async for c in make_brain(handler).turn_stream("room-1", "hi")]


@pytest.mark.asyncio
async def test_turn_stream_connect_error_is_a_brain_error():
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("connection refused")

    with pytest.raises(BrainError, match="could not stream"):
        [c async for c in make_brain(handler).turn_stream("room-1", "hi")]
