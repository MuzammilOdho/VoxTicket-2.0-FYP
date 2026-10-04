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
