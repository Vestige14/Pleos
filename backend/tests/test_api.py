import asyncio
import json
import logging
from contextlib import asynccontextmanager

import httpx
import pytest

from tests.support import FakeModel, payload

TOKEN = "synthetic-local-session-token-0000000001"
AUTH = {"Authorization": f"Bearer {TOKEN}"}


@asynccontextmanager
async def connection(model=None):
    from app.link_service import LinkService
    from app.main import create_app
    model = model or FakeModel()
    app = create_app(session_token=TOKEN, service=LinkService(model))
    async with app.router.lifespan_context(app):
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://127.0.0.1") as client:
            yield app, client, model


async def test_authenticated_request_returns_only_server_echo_and_validated_spans():
    async with connection() as (_, client, model):
        response = await client.post("/v1/memory-links", headers=AUTH, json=payload())
        assert response.status_code == 200
        assert response.json() == {"requestId": "job-01", "noteRevision": 1, "userRevision": 0,
                                   "contextHash": "snapshot-01", "decision": "SUGGEST",
                                   "links": [{"visitId": "v1", "startCp": 3, "endCp": 15}]}
        assert response.headers["cache-control"] == "no-store"
        assert model.calls == 1


@pytest.mark.parametrize("headers", [{}, {"Authorization": "Bearer wrong"}, {"Authorization": f"Basic {TOKEN}"}, [("Authorization", f"Bearer {TOKEN}"), ("Authorization", f"Bearer {TOKEN}")]])
async def test_authentication_rejects_before_reading_malformed_or_oversized_body(headers):
    reads = []
    async def body():
        reads.append(True)
        yield b"private malformed" * 10_000
    async with connection() as (_, client, model):
        response = await client.post("/v1/memory-links", headers=headers, content=body())
        assert response.status_code == 401
        assert response.json() == {"error": {"code": "UNAUTHORIZED", "retryable": False}}
        assert response.headers["www-authenticate"] == "Bearer"
        assert reads == []
        assert model.calls == 0


@pytest.mark.parametrize("content", [b'{"text":"private-json-sentinel"', b'\xff', b'{"text": NaN}', b'{"text": "a", "text": "b"}'])
async def test_malformed_json_errors_do_not_reflect_input(content):
    async with connection() as (_, client, model):
        response = await client.post("/v1/memory-links", headers={**AUTH, "Content-Type": "application/json"}, content=content)
        assert response.status_code == 400
        assert response.json() == {"error": {"code": "INVALID_JSON", "retryable": False}}
        assert "private-json-sentinel" not in response.text
        assert model.calls == 0


@pytest.mark.parametrize("change", [
    {"text": ""}, {"text": "a" * 4001}, {"noteRevision": True},
    {"capturedAt": "2026-11-08T15:30:00"}, {"private-field-sentinel": "private-value-sentinel"},
    {"candidates": payload()["candidates"] * 7},
    {"candidates": [payload()["candidates"][0]] * 2},
])
async def test_invalid_request_never_calls_model_or_echoes_values(change):
    async with connection() as (_, client, model):
        response = await client.post("/v1/memory-links", headers=AUTH, json=payload(**change))
        assert response.status_code == 422
        assert response.json() == {"error": {"code": "INVALID_REQUEST", "retryable": False}}
        assert "private-field-sentinel" not in response.text
        assert "private-value-sentinel" not in response.text
        assert model.calls == 0


async def test_body_limit_checks_streamed_bytes_even_with_small_content_length():
    async def body():
        yield b" " * 32_768
        yield b" " * 32_768
        yield b"x"
    async with connection() as (_, client, model):
        response = await client.post("/v1/memory-links", headers={**AUTH, "Content-Type": "application/json", "Content-Length": "1"}, content=body())
        assert response.status_code == 413
        assert response.json() == {"error": {"code": "BODY_TOO_LARGE", "retryable": False}}
        assert model.calls == 0


async def test_declared_large_body_is_rejected_without_reading_stream():
    reads = []
    async def body():
        reads.append(True)
        yield b"x"
    async with connection() as (_, client, model):
        response = await client.post("/v1/memory-links", headers={**AUTH, "Content-Type": "application/json", "Content-Length": "65537"}, content=body())
        assert response.status_code == 413
        assert reads == []
        assert model.calls == 0


@pytest.mark.parametrize("headers,status,code", [
    ({"Content-Type": "text/plain"}, 415, "UNSUPPORTED_MEDIA_TYPE"),
    ({"Content-Type": "application/json", "Content-Encoding": "gzip"}, 415, "UNSUPPORTED_MEDIA_TYPE"),
    ({"Content-Type": "application/json", "Content-Length": "oops"}, 400, "INVALID_BODY"),
    ({"Content-Type": "application/json", "Content-Length": "-1"}, 400, "INVALID_BODY"),
])
async def test_invalid_body_metadata_is_rejected_before_external_call(headers, status, code):
    async with connection() as (_, client, model):
        response = await client.post("/v1/memory-links", headers={**AUTH, **headers}, content=b"{}")
        assert (response.status_code, response.json()["error"]["code"]) == (status, code)
        assert model.calls == 0


async def test_http_idempotency_cache_and_payload_conflict_are_visible():
    async with connection() as (_, client, model):
        first = await client.post("/v1/memory-links", headers=AUTH, json=payload())
        second = await client.post("/v1/memory-links", headers=AUTH, json=payload())
        conflict = await client.post("/v1/memory-links", headers=AUTH, json=payload(text="changed"))
        assert second.json() == first.json()
        assert (conflict.status_code, conflict.json()["error"]["code"]) == (409, "REQUEST_ID_CONFLICT")
        assert model.calls == 1


async def test_transient_retry_header_and_nonretryable_unknown_outcome_are_distinct():
    from app.schemas import Failure, ModelCallError
    async with connection(FakeModel(ModelCallError(Failure(429, "UPSTREAM_RATE_LIMIT", True, 75)))) as (_, client, model):
        response = await client.post("/v1/memory-links", headers=AUTH, json=payload())
        assert response.status_code == 429
        assert response.headers["retry-after"] == "75"
        assert response.json() == {"error": {"code": "UPSTREAM_RATE_LIMIT", "retryable": True}}
    async with connection(FakeModel(RuntimeError("private-failure-sentinel"))) as (_, client, model):
        response = await client.post("/v1/memory-links", headers=AUTH, json=payload())
        assert response.status_code == 503
        assert response.json() == {"error": {"code": "OUTCOME_UNKNOWN", "retryable": False}}
        assert "retry-after" not in response.headers


async def test_validation_or_model_failures_do_not_log_original_places_or_authorization(caplog):
    caplog.set_level(logging.DEBUG)
    async with connection(FakeModel(RuntimeError("private-exception-sentinel"))) as (_, client, model):
        await client.post("/v1/memory-links", headers=AUTH, json=payload(text="private-original-sentinel"))
        await client.post("/v1/memory-links", headers=AUTH, json=payload(**{"private-field-sentinel": "private-value-sentinel"}))
        await client.post("/v1/memory-links", headers={**AUTH, "Content-Type": "application/json"}, content=b'{"private-json-sentinel"')
    for sentinel in ["private-exception-sentinel", "private-original-sentinel", "private-value-sentinel", "private-json-sentinel", "대부도 칼국수집", TOKEN, "Authorization"]:
        assert sentinel not in caplog.text


async def test_lifespan_closes_service_resources():
    model = FakeModel()
    async with connection(model):
        assert not model.closed
    assert model.closed


@pytest.mark.parametrize("token", ["", "short", "x" * 257, " " * 32])
def test_session_token_must_be_nonempty_bounded_random_ascii(token):
    from app.main import create_app
    from app.link_service import LinkService
    with pytest.raises(RuntimeError, match="session token"):
        create_app(session_token=token, service=LinkService(FakeModel()))


def test_session_token_is_read_from_environment_not_a_fallback(monkeypatch):
    from app.main import create_app
    from app.link_service import LinkService
    monkeypatch.delenv("MEMORY_LINK_SESSION_TOKEN", raising=False)
    with pytest.raises(RuntimeError, match="session token"):
        create_app(service=LinkService(FakeModel()))
    monkeypatch.setenv("MEMORY_LINK_SESSION_TOKEN", TOKEN)
    assert create_app(service=LinkService(FakeModel())).state.service is not None


@pytest.mark.parametrize("field", ["capturedAt", "arrivedAt", "leftAt"])
@pytest.mark.parametrize("offset", ["+09:60", "+00:99"])
async def test_invalid_offset_components_are_422_before_model(field, offset):
    # All other instants/intervals remain valid after Python's lenient normalization;
    # only rejecting the invalid offset itself can satisfy this boundary test.
    data = payload(capturedAt="2026-11-08T23:30:00+09:00", candidates=[{
        "id": "v1", "placeName": "합성 식당", "arrivedAt": "2026-11-08T00:00:00+09:00",
        "leftAt": "2026-11-09T02:00:00+09:00",
    }])
    if field == "capturedAt":
        data[field] = "2026-11-08T12:00:00" + offset
    else:
        data["candidates"][0][field] = "2026-11-08T12:00:00" + offset
    async with connection() as (_, client, model):
        response = await client.post("/v1/memory-links", headers=AUTH, json=data)
        assert response.status_code == 422
        assert response.json() == {"error": {"code": "INVALID_REQUEST", "retryable": False}}
        assert model.calls == 0
