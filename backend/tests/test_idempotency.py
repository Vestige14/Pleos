import asyncio

import pytest

from tests.support import FakeModel, payload, suggestion


class Clock:
    def __init__(self):
        self.value = 1000.0

    def __call__(self):
        return self.value


def service(model=None, clock=None, **limits):
    from app.idempotency_store import IdempotencyStore
    from app.link_service import LinkService
    return LinkService(model or FakeModel(), store=IdempotencyStore(clock=clock or Clock(), **limits))


def request(**changes):
    from app.schemas import LinkRequest
    return LinkRequest.model_validate(payload(**changes))


async def test_same_id_returns_cached_result_without_repeated_external_call():
    model = FakeModel()
    subject = service(model)
    first = await subject.link(request())
    model.result = {"decision": "NO_MATCH", "links": []}
    second = await subject.link(request())
    assert second == first
    assert model.calls == 1
    await subject.aclose()


async def test_simultaneous_duplicates_share_one_inflight_result():
    model = FakeModel()
    model.release = asyncio.Event()
    subject = service(model)
    tasks = [asyncio.create_task(subject.link(request())) for _ in range(12)]
    await asyncio.wait_for(model.started.wait(), 1)
    await asyncio.sleep(0)
    model.release.set()
    results = await asyncio.gather(*tasks)
    assert all(value == results[0] for value in results)
    assert results[0].decision == "SUGGEST"
    assert model.calls == 1
    await subject.aclose()


async def test_same_id_different_body_conflicts_even_while_inflight():
    model = FakeModel()
    model.release = asyncio.Event()
    subject = service(model)
    task = asyncio.create_task(subject.link(request()))
    await asyncio.wait_for(model.started.wait(), 1)
    conflict = await subject.link(request(text="changed"))
    assert (conflict.status, conflict.code, conflict.retryable) == (409, "REQUEST_ID_CONFLICT", False)
    model.release.set()
    await task
    again = await subject.link(request(noteRevision=2))
    assert again.status == 409
    assert model.calls == 1
    await subject.aclose()


async def test_canonical_hash_ignores_json_key_order():
    from app.schemas import LinkRequest
    subject = service()
    first = await subject.link(request())
    reordered = dict(reversed(list(payload().items())))
    result = await subject.link(LinkRequest.model_validate(reordered))
    assert result == first
    assert subject.model.calls == 1
    await subject.aclose()


async def test_cache_is_reused_before_24h_and_expires_at_24h():
    clock = Clock()
    subject = service(clock=clock)
    await subject.link(request())
    clock.value += 86_399
    await subject.link(request())
    assert subject.model.calls == 1
    clock.value += 1
    await subject.link(request())
    assert subject.model.calls == 2
    await subject.aclose()


async def test_completed_cache_contains_no_original_text_place_or_quote():
    subject = service()
    await subject.link(request(text="비공개합성문구 유일한 문장"))
    metadata = repr(subject.store._entries)
    for value in ["비공개합성문구", "대부도 칼국수집", "칼국수집 국물이 좋았어", "evidenceQuote", "LinkRequest"]:
        assert value not in metadata
    assert "job-01" in metadata
    await subject.aclose()


async def test_transient_failure_waits_for_retry_after_and_then_can_succeed():
    from app.schemas import Failure, ModelCallError
    clock = Clock()
    model = FakeModel(ModelCallError(Failure(429, "UPSTREAM_RATE_LIMIT", True, 90)))
    subject = service(model, clock)
    first = await subject.link(request())
    assert first.retry_after == 90
    clock.value += 89
    waiting = await subject.link(request())
    assert (waiting.code, waiting.retry_after, model.calls) == ("UPSTREAM_RATE_LIMIT", 1, 1)
    clock.value += 1
    model.result = suggestion()
    result = await subject.link(request())
    assert result.decision == "SUGGEST"
    assert model.calls == 2
    await subject.aclose()


async def test_transient_calls_stop_after_five_actual_attempts():
    from app.schemas import Failure, ModelCallError
    clock = Clock()
    subject = service(FakeModel(ModelCallError(Failure(503, "UPSTREAM_UNAVAILABLE", True))), clock)
    for attempt in range(5):
        result = await subject.link(request())
        assert result.retry_after == 30 * 2**attempt
        clock.value += result.retry_after
    exhausted = await subject.link(request())
    assert (exhausted.code, exhausted.retryable) == ("ATTEMPTS_EXHAUSTED", False)
    assert subject.model.calls == 5
    await subject.aclose()


async def test_unknown_outcome_and_unexpected_exception_are_not_recalled_or_cached_as_traceback():
    subject = service(FakeModel(RuntimeError("비공개예외원문")))
    for _ in range(2):
        result = await subject.link(request())
        assert (result.status, result.code, result.retryable) == (503, "OUTCOME_UNKNOWN", False)
    assert subject.model.calls == 1
    assert "비공개예외원문" not in repr(subject.store._entries)
    await subject.aclose()


async def test_waiter_cancellation_does_not_cancel_shared_call():
    model = FakeModel()
    model.release = asyncio.Event()
    subject = service(model)
    first = asyncio.create_task(subject.link(request()))
    await asyncio.wait_for(model.started.wait(), 1)
    second = asyncio.create_task(subject.link(request()))
    first.cancel()
    with pytest.raises(asyncio.CancelledError):
        await first
    model.release.set()
    assert (await second).decision == "SUGGEST"
    assert (await subject.link(request())).decision == "SUGGEST"
    assert model.calls == 1
    await subject.aclose()


async def test_shutdown_cancels_bounded_work_and_releases_slots_and_client():
    model = FakeModel()
    model.release = asyncio.Event()
    subject = service(model)
    task = asyncio.create_task(subject.link(request()))
    await asyncio.wait_for(model.started.wait(), 1)
    await asyncio.wait_for(subject.aclose(), 1)
    result = await task
    assert (result.code, result.retryable) == ("OUTCOME_UNKNOWN", False)
    assert subject.store._inflight == 0
    assert all(entry.task is None for entry in subject.store._entries.values())
    assert model.closed


async def test_inflight_capacity_rejection_does_not_consume_or_leak_slot():
    model = FakeModel()
    model.release = asyncio.Event()
    subject = service(model, max_inflight=1)
    task = asyncio.create_task(subject.link(request()))
    await asyncio.wait_for(model.started.wait(), 1)
    rejected = await subject.link(request(requestId="job-02"))
    assert (rejected.status, rejected.code, rejected.retryable) == (429, "SERVER_BUSY", True)
    assert model.calls == 1
    model.release.set()
    await task
    assert (await subject.link(request(requestId="job-02"))).decision == "SUGGEST"
    assert model.calls == 2
    assert subject.store._inflight == 0
    await subject.aclose()


async def test_call_rate_limit_excludes_cache_hits_and_recovers_at_window_end():
    clock = Clock()
    subject = service(clock=clock, calls_per_minute=1)
    await subject.link(request())
    assert (await subject.link(request())).decision == "SUGGEST"
    rejected = await subject.link(request(requestId="job-02"))
    assert (rejected.status, rejected.code, rejected.retry_after) == (429, "CALL_RATE_LIMIT", 60)
    assert subject.model.calls == 1
    clock.value += 60
    assert (await subject.link(request(requestId="job-02"))).decision == "SUGGEST"
    assert subject.model.calls == 2
    await subject.aclose()


async def test_full_cache_rejects_new_key_without_evicting_a_live_key():
    clock = Clock()
    subject = service(clock=clock, max_entries=1)
    await subject.link(request())
    blocked = await subject.link(request(requestId="job-02"))
    assert (blocked.status, blocked.code) == (429, "CACHE_CAPACITY")
    assert (await subject.link(request())).decision == "SUGGEST"
    assert subject.model.calls == 1
    clock.value += 86_400
    assert (await subject.link(request(requestId="job-02"))).decision == "SUGGEST"
    assert subject.model.calls == 2
    await subject.aclose()


async def test_empty_candidates_do_not_use_model_call_quota():
    subject = service(calls_per_minute=1)
    for i in range(3):
        result = await subject.link(request(requestId=f"empty-{i}", candidates=[]))
        assert result.decision == "NO_MATCH"
    assert (await subject.link(request())).decision == "SUGGEST"
    assert subject.model.calls == 1
    await subject.aclose()


async def test_total_timeout_records_unknown_and_recovers_capacity():
    from app.link_service import LinkService
    from app.idempotency_store import IdempotencyStore
    model = FakeModel()
    model.release = asyncio.Event()
    subject = LinkService(model, store=IdempotencyStore(max_inflight=1), timeout_seconds=0.02)
    result = await asyncio.wait_for(subject.link(request()), 1)
    assert (result.code, result.retryable) == ("OUTCOME_UNKNOWN", False)
    assert (await subject.link(request())).code == "OUTCOME_UNKNOWN"
    model.release.set()
    assert (await subject.link(request(requestId="job-02"))).decision == "SUGGEST"
    assert model.calls == 2
    assert subject.store._inflight == 0
    await subject.aclose()


async def test_expired_cache_is_removed_without_another_request():
    import time
    subject = service(clock=time.monotonic, ttl_seconds=0.02)
    await subject.link(request())
    await asyncio.sleep(0.04)
    assert subject.store._entries == {}
    await subject.aclose()


async def test_shutdown_still_cancels_work_whose_cache_entry_has_expired():
    import time
    model = FakeModel()
    model.release = asyncio.Event()
    subject = service(model, clock=time.monotonic, ttl_seconds=0.02)
    waiter = asyncio.create_task(subject.link(request()))
    await asyncio.wait_for(model.started.wait(), 1)
    await asyncio.sleep(0.04)
    assert subject.store._entries == {}
    await subject.aclose()
    assert subject.store._inflight == 0
    assert (await asyncio.wait_for(waiter, 1)).code == "OUTCOME_UNKNOWN"
