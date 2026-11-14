"""The approved app setting must reach real SDK wire JSON, with no model fallback."""

import json

import httpx
import pytest

from tests.test_openai_adapter import envelope, request


def make_adapter(monkeypatch, handler, effort=None):
    from app.openai_adapter import OpenAILinkAdapter
    monkeypatch.setenv("OPENAI_API_KEY", "synthetic-test-credential-not-a-key")
    monkeypatch.setenv("OPENAI_LINK_MODEL", "gpt-6.1-terra")
    if effort is None:
        monkeypatch.delenv("OPENAI_LINK_REASONING_EFFORT", raising=False)
    else:
        monkeypatch.setenv("OPENAI_LINK_REASONING_EFFORT", effort)
    return OpenAILinkAdapter.from_env(http_client=httpx.AsyncClient(transport=httpx.MockTransport(handler)))


@pytest.mark.parametrize("effort,want", [(None, "low"), ("none", "none"), ("low", "low"), ("medium", "medium"), ("high", "high"), ("xhigh", "xhigh"), ("max", "max")])
async def test_env_effort_and_standard_tier_are_explicit_on_single_nonstored_wire(monkeypatch, effort, want):
    observed = []
    def handler(req):
        observed.append(json.loads(req.content))
        return httpx.Response(200, json=envelope())
    adapter = make_adapter(monkeypatch, handler, effort)
    try:
        await adapter.suggest(request())
        assert len(observed) == 1
        assert observed[0]["model"] == "gpt-6.1-terra"
        assert observed[0]["reasoning"] == {"effort": want}
        assert observed[0]["service_tier"] == "default"
        assert observed[0]["store"] is False
        assert observed[0]["max_output_tokens"] == 2048
        assert adapter.client.max_retries == 0
    finally:
        await adapter.aclose()


@pytest.mark.parametrize("effort", ["", " ", "LOW", "minimal", "automatic", "secret-private-invalid-value"])
async def test_invalid_effort_fails_before_any_model_call_without_reflecting_value(monkeypatch, effort):
    client = httpx.AsyncClient(transport=httpx.MockTransport(lambda _: pytest.fail("must not call provider")))
    from app.openai_adapter import OpenAILinkAdapter
    monkeypatch.setenv("OPENAI_API_KEY", "synthetic-test-credential-not-a-key")
    monkeypatch.setenv("OPENAI_LINK_MODEL", "gpt-6.1-terra")
    monkeypatch.setenv("OPENAI_LINK_REASONING_EFFORT", effort)
    try:
        with pytest.raises((ValueError, RuntimeError)) as caught:
            OpenAILinkAdapter.from_env(http_client=client)
        assert "secret-private-invalid-value" not in str(caught.value)
    finally:
        await client.aclose()


async def test_unavailable_model_does_not_fallback_or_increase_sdk_retries(monkeypatch):
    from app.schemas import ModelCallError
    calls = []
    def handler(req):
        calls.append(json.loads(req.content))
        return httpx.Response(404, json={"error": {"type": "invalid_request_error", "code": "model_not_found", "message": "private provider text"}})
    adapter = make_adapter(monkeypatch, handler, "low")
    try:
        with pytest.raises(ModelCallError) as caught:
            await adapter.suggest(request())
        assert caught.value.failure.code == "UPSTREAM_CONFIG"
        assert caught.value.failure.retryable is False
        assert len(calls) == 1
        assert calls[0]["model"] == "gpt-6.1-terra"
        assert calls[0]["service_tier"] == "default"
    finally:
        await adapter.aclose()
