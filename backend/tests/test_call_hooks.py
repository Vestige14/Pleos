"""Local admission/usage seams carry metadata only, never a price or original text."""

import asyncio
from dataclasses import asdict

import httpx
import pytest

from tests.test_openai_adapter import envelope, request


def adapter(monkeypatch, handler, **hooks):
    from app.openai_adapter import OpenAILinkAdapter
    monkeypatch.setenv("OPENAI_API_KEY", "synthetic-test-credential-not-a-key")
    monkeypatch.setenv("OPENAI_LINK_MODEL", "gpt-6.1-terra")
    monkeypatch.delenv("OPENAI_LINK_REASONING_EFFORT", raising=False)
    return OpenAILinkAdapter.from_env(http_client=httpx.AsyncClient(transport=httpx.MockTransport(handler)), **hooks)


async def test_admission_rejection_prevents_provider_and_cannot_reflect_private_error(monkeypatch):
    from app.schemas import ModelCallError
    calls = []
    async def deny(metadata):
        assert set(asdict(metadata)) == {"request_id", "model", "reasoning_effort", "max_output_tokens"}
        return False
    subject = adapter(monkeypatch, lambda req: calls.append(req), admit_call=deny)
    try:
        with pytest.raises(ModelCallError) as caught:
            await subject.suggest(request())
        assert caught.value.failure.code == "ADMISSION_REJECTED"
        assert caught.value.failure.retryable is False
        assert calls == []
    finally:
        await subject.aclose()


async def test_admission_exception_is_closed_before_provider(monkeypatch):
    from app.schemas import ModelCallError
    async def broken(_):
        raise RuntimeError("synthetic-private-admission-detail")
    subject = adapter(monkeypatch, lambda _: pytest.fail("no provider"), admit_call=broken)
    try:
        with pytest.raises(ModelCallError) as caught:
            await subject.suggest(request())
        assert caught.value.failure.code == "ADMISSION_REJECTED"
        assert "private" not in str(caught.value)
    finally:
        await subject.aclose()


async def test_usage_records_sdk_counts_and_actual_tier_without_request_or_response_content(monkeypatch):
    events = []
    async def usage(event):
        events.append(asdict(event))
    body = envelope()
    body["service_tier"] = "default"
    subject = adapter(monkeypatch, lambda _: httpx.Response(200, json=body), on_usage=usage)
    try:
        await subject.suggest(request())
        assert events == [{"request_id": "job-01", "model": "gpt-6.1-terra", "reasoning_effort": "low",
                           "service_tier": "default", "input_tokens": 10, "output_tokens": 5,
                           "total_tokens": 15, "reasoning_tokens": 0, "outcome": "RECEIVED"}]
        assert "칼국수" not in str(events)
        assert "synthetic-test-credential" not in str(events)
    finally:
        await subject.aclose()


async def test_transport_unknown_records_unknown_usage_and_has_no_automatic_retry(monkeypatch):
    from app.schemas import ModelCallError
    events = []
    calls = []
    async def usage(event):
        events.append(event)
    def fail(req):
        calls.append(req)
        raise httpx.ReadTimeout("private transport", request=req)
    subject = adapter(monkeypatch, fail, on_usage=usage)
    try:
        with pytest.raises(ModelCallError) as caught:
            await subject.suggest(request())
        assert caught.value.failure.code == "OUTCOME_UNKNOWN"
        assert len(calls) == 1 and len(events) == 1
        assert events[0].outcome == "OUTCOME_UNKNOWN"
        assert events[0].input_tokens is None and events[0].output_tokens is None
    finally:
        await subject.aclose()


async def test_failed_usage_sink_stops_instead_of_repeating_paid_call(monkeypatch):
    from app.schemas import ModelCallError
    calls = []
    async def broken(_):
        raise RuntimeError("private usage detail")
    def provider(req):
        calls.append(req)
        return httpx.Response(200, json=envelope())
    subject = adapter(monkeypatch, provider, on_usage=broken)
    try:
        with pytest.raises(ModelCallError) as caught:
            await subject.suggest(request())
        assert caught.value.failure.code == "USAGE_RECORDING_FAILED"
        assert caught.value.failure.retryable is False and len(calls) == 1
        assert "private" not in str(caught.value)
    finally:
        await subject.aclose()


async def test_completed_cache_reuse_does_not_readmit_or_double_record_usage(monkeypatch):
    from app.link_service import LinkService
    admitted, recorded, calls = [], [], []
    async def admit(meta):
        admitted.append(meta.request_id)
        return True
    async def usage(event):
        recorded.append(event.request_id)
    def provider(req):
        calls.append(req)
        return httpx.Response(200, json=envelope())
    service = LinkService(adapter(monkeypatch, provider, admit_call=admit, on_usage=usage))
    try:
        first = await service.link(request())
        assert await service.link(request()) == first
        assert len(calls) == 1 and admitted == ["job-01"] and recorded == ["job-01"]
    finally:
        await service.aclose()


async def test_slow_admission_is_bounded_before_any_http(monkeypatch):
    from app.schemas import ModelCallError
    async def wait(_):
        await asyncio.Event().wait()
    subject = adapter(monkeypatch, lambda _: pytest.fail("no provider"), admit_call=wait, timeout_seconds=0.01)
    try:
        with pytest.raises(ModelCallError) as caught:
            await asyncio.wait_for(subject.suggest(request()), 1)
        assert caught.value.failure.code == "ADMISSION_REJECTED"
    finally:
        await subject.aclose()


async def test_service_deadline_records_unknown_usage_when_outer_scope_cancels_adapter(monkeypatch):
    from app.link_service import LinkService
    events = []
    released = asyncio.Event()
    entered = asyncio.Event()
    async def usage(event):
        events.append(event)
    async def wait(_):
        entered.set()
        try:
            await asyncio.Event().wait()
        finally:
            released.set()
    service = LinkService(adapter(monkeypatch, wait, on_usage=usage), timeout_seconds=1)
    try:
        operation = asyncio.create_task(service.link(request()))
        await asyncio.wait_for(entered.wait(), 1)
        result = await asyncio.wait_for(operation, 2)
        assert result.code == "OUTCOME_UNKNOWN" and result.retryable is False
        assert released.is_set()
        assert len(events) == 1 and events[0].outcome == "OUTCOME_UNKNOWN"
        assert events[0].total_tokens is None
    finally:
        await service.aclose()


async def test_cancellation_records_unknown_usage_and_still_propagates(monkeypatch):
    events = []
    entered = asyncio.Event()
    released = asyncio.Event()
    async def usage(event):
        events.append(event)
    async def wait(_):
        entered.set()
        try:
            await asyncio.Event().wait()
        finally:
            released.set()
    subject = adapter(monkeypatch, wait, on_usage=usage)
    try:
        task = asyncio.create_task(subject.suggest(request()))
        await entered.wait()
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task
        assert released.is_set()
        assert len(events) == 1 and events[0].outcome == "OUTCOME_UNKNOWN"
    finally:
        await subject.aclose()
