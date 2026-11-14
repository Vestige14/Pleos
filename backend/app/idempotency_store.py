"""Bounded process-local single-flight cache. No original text or model quotes at rest."""

import asyncio
import math
import time
from collections import deque
from collections.abc import Awaitable, Callable
from dataclasses import dataclass, replace

from .schemas import Failure, LinkResponse

Outcome = LinkResponse | Failure


@dataclass(slots=True)
class Entry:
    body_hash: str
    expires_at: float
    attempts: int = 0
    outcome: Outcome | None = None
    retry_at: float = 0
    task: asyncio.Task[Outcome] | None = None


class IdempotencyStore:
    def __init__(self, *, clock: Callable[[], float] = time.monotonic,
                 ttl_seconds: float = 86_400, max_entries: int = 1024,
                 max_inflight: int = 4, calls_per_minute: int = 30):
        if not 0 < ttl_seconds <= 86_400 or min(max_entries, max_inflight, calls_per_minute) < 1:
            raise ValueError("invalid store limits")
        self.clock = clock
        self.ttl_seconds = ttl_seconds
        self.max_entries = max_entries
        self.max_inflight = max_inflight
        self.calls_per_minute = calls_per_minute
        self._entries: dict[str, Entry] = {}
        self._expiry: dict[str, asyncio.TimerHandle] = {}
        self._tasks: set[asyncio.Task[Outcome]] = set()
        self._starts: deque[float] = deque()
        self._inflight = 0
        self._lock = asyncio.Lock()
        self._closed = False

    async def execute(self, key: str, body_hash: str, work: Callable[[], Awaitable[Outcome]],
                      *, uses_model: bool) -> Outcome:
        async with self._lock:
            now = self.clock()
            if self._closed:
                return Failure(503, "SERVER_CLOSED", False)
            for expired_key in [key for key, value in self._entries.items() if value.expires_at <= now]:
                self._discard(expired_key)
            while self._starts and self._starts[0] <= now - 60:
                self._starts.popleft()
            entry = self._entries.get(key)
            if entry is not None and entry.body_hash != body_hash:
                return Failure(409, "REQUEST_ID_CONFLICT", False)
            if entry is None:
                if len(self._entries) >= self.max_entries:
                    retry = math.ceil(min(value.expires_at for value in self._entries.values()) - now)
                    return Failure(429, "CACHE_CAPACITY", True, max(1, retry))
                entry = Entry(body_hash, now + self.ttl_seconds)
                self._entries[key] = entry
                self._expiry[key] = asyncio.get_running_loop().call_later(
                    self.ttl_seconds, self._expire, key, entry)
            if entry.task is not None:
                task = entry.task
            else:
                if entry.outcome is not None:
                    if not isinstance(entry.outcome, Failure) or not entry.outcome.retryable:
                        return entry.outcome
                    if entry.retry_at > now:
                        return replace(entry.outcome, retry_after=max(1, math.ceil(entry.retry_at - now)))
                if entry.attempts >= 5:
                    return Failure(503, "ATTEMPTS_EXHAUSTED", False)
                if uses_model:
                    if self._inflight >= self.max_inflight:
                        return Failure(429, "SERVER_BUSY", True, 1)
                    if len(self._starts) >= self.calls_per_minute:
                        return Failure(429, "CALL_RATE_LIMIT", True, max(1, math.ceil(self._starts[0] + 60 - now)))
                    self._inflight += 1
                    self._starts.append(now)
                    entry.attempts += 1
                task = asyncio.create_task(self._run(entry, work, uses_model))
                entry.task = task
                self._tasks.add(task)
                task.add_done_callback(self._tasks.discard)
        # A disconnected/cancelled waiter must not cancel a call other waiters share.
        return await asyncio.shield(task)

    async def _run(self, entry: Entry, work: Callable[[], Awaitable[Outcome]], uses_model: bool) -> Outcome:
        try:
            outcome = await work()
        except (asyncio.CancelledError, Exception):
            # A transport or unexpected failure can have an uncertain paid outcome.
            # Discard the exception rather than retaining a traceback with request locals.
            outcome = Failure(503, "OUTCOME_UNKNOWN", False)
        async with self._lock:
            if uses_model:
                self._inflight -= 1
            if isinstance(outcome, Failure) and outcome.retryable:
                delay = max(outcome.retry_after or 0, 30 * 2 ** max(0, entry.attempts - 1))
                outcome = replace(outcome, retry_after=delay)
                entry.retry_at = self.clock() + delay
            entry.outcome = outcome
            entry.task = None
        return outcome

    def _discard(self, key: str) -> None:
        self._entries.pop(key, None)
        timer = self._expiry.pop(key, None)
        if timer is not None:
            timer.cancel()

    def _expire(self, key: str, entry: Entry) -> None:
        # The event loop runs this even when no later request triggers lazy expiry.
        # The identity check prevents an old timer deleting a replacement entry.
        if self._entries.get(key) is entry:
            self._discard(key)

    async def aclose(self) -> None:
        async with self._lock:
            self._closed = True
            tasks = list(self._tasks)
        # Let newly scheduled runners enter their try block before cancellation.
        await asyncio.sleep(0)
        for task in tasks:
            task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)
        async with self._lock:
            for key in list(self._entries):
                self._discard(key)
            self._starts.clear()
