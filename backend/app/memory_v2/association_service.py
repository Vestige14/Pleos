"""Check experience dates before a paid call, then accept supplied IDs and exact spans only."""

import asyncio
import hashlib
import json
from typing import Protocol

from pydantic import ValidationError

from ..idempotency_store import IdempotencyStore
from ..schemas import Failure, ModelCallError
from .schemas import MemoryLinkRequest, MemoryLinkResponse, MemoryLinkSpan, ModelMemoryResult
from .temporal import resolve_time


class AssociationModel(Protocol):
    async def suggest(self, request: MemoryLinkRequest) -> object: ...
    async def aclose(self) -> None: ...


class MemoryAssociationService:
    def __init__(self, model: AssociationModel, *, store: IdempotencyStore | None = None,
                 timeout_seconds: float = 30):
        if not 0 < timeout_seconds <= 30:
            raise ValueError("invalid association timeout")
        self.model = model
        self.store = store if store is not None else IdempotencyStore()
        self.timeout_seconds = timeout_seconds

    async def link(self, request: MemoryLinkRequest) -> MemoryLinkResponse | Failure:
        canonical = json.dumps(request.model_dump(mode="json"), sort_keys=True,
                               separators=(",", ":"), ensure_ascii=False).encode("utf-8")
        body_hash = hashlib.sha256(canonical).hexdigest()
        resolved = resolve_time(request.text, request.capturedAt)
        if (request.timeScope.mode == "UNRESOLVED" or resolved.mode == "UNRESOLVED"
                or resolved.mode != request.timeScope.mode or resolved.event_date != request.timeScope.eventDate):
            decision = "REVIEW"
            candidates = []
        else:
            candidates = [candidate for candidate in request.candidates
                          if candidate.evidenceDate == resolved.event_date
                          and candidate.timeBasis != "UNDATED_RECENT"]
            decision = None if candidates else "NO_MATCH"
        # Model receives only allowed candidates; the original full snapshot remains
        # the idempotency identity so changed/excluded context cannot replay a result.
        selected = request.model_copy(update={"candidates": candidates})
        key = "v2/memory:" + hashlib.sha512(request.requestId.encode("utf-8")).hexdigest()
        return await self.store.execute(key, body_hash, lambda: self._generate(selected, decision),
                                        uses_model=decision is None)

    async def _generate(self, request: MemoryLinkRequest, decision: str | None) -> MemoryLinkResponse | Failure:
        if decision is not None:
            return self._response(request, decision)
        try:
            async with asyncio.timeout(self.timeout_seconds):
                raw = await self.model.suggest(request)
        except ModelCallError as exc:
            return exc.failure
        except TimeoutError:
            return Failure(503, "OUTCOME_UNKNOWN", False)
        return self._validate(request, raw)

    def _validate(self, request: MemoryLinkRequest, raw: object) -> MemoryLinkResponse:
        try:
            result = ModelMemoryResult.model_validate(raw)
        except (ValidationError, ValueError):
            return self._response(request, "REVIEW")
        if result.decision != "SUGGEST":
            return self._response(request, result.decision if not result.links else "REVIEW")
        if not result.links:
            return self._response(request, "REVIEW")
        candidates = {candidate.id: candidate for candidate in request.candidates}
        used_ids: set[str] = set()
        used_spans: set[tuple[int, int]] = set()
        spans = []
        for link in result.links:
            candidate = candidates.get(link.targetId)
            # Same exact-quote/code-point algorithm as v1, including overlapping
            # repeat detection. No model-provided indices or rewritten text survive.
            start = request.text.find(link.evidenceQuote)
            if (candidate is None or candidate.kind != link.kind or link.targetId in used_ids
                    or start < 0 or request.text.find(link.evidenceQuote, start + 1) >= 0):
                return self._response(request, "REVIEW")
            end = start + len(link.evidenceQuote)
            if (start, end) in used_spans:
                return self._response(request, "REVIEW")
            used_ids.add(link.targetId)
            used_spans.add((start, end))
            spans.append(MemoryLinkSpan(targetId=link.targetId, kind=link.kind, startCp=start, endCp=end))
        return self._response(request, "SUGGEST", tuple(spans))

    @staticmethod
    def _response(request: MemoryLinkRequest, decision: str, links=()) -> MemoryLinkResponse:
        return MemoryLinkResponse(requestId=request.requestId, noteRevision=request.noteRevision,
                                  userRevision=request.userRevision, contextHash=request.contextHash,
                                  decision=decision, links=links)

    async def aclose(self) -> None:
        await self.store.aclose()
        await self.model.aclose()
