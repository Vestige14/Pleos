"""Only validated candidate IDs and unique original-text spans leave the service."""

import asyncio
import hashlib
import json
from typing import Protocol

from pydantic import ValidationError

from .idempotency_store import IdempotencyStore, Outcome
from .schemas import Failure, LinkRequest, LinkResponse, LinkSpan, ModelCallError, ModelResult


class LinkModel(Protocol):
    async def suggest(self, request: LinkRequest) -> object: ...
    async def aclose(self) -> None: ...


class LinkService:
    def __init__(self, model: LinkModel, *, store: IdempotencyStore | None = None,
                 timeout_seconds: float = 30):
        self.model = model
        self.store = store if store is not None else IdempotencyStore()
        if not 0 < timeout_seconds <= 30:
            raise ValueError("invalid model timeout")
        self.timeout_seconds = timeout_seconds

    async def link(self, request: LinkRequest) -> Outcome:
        canonical = json.dumps(request.model_dump(mode="json"), sort_keys=True,
                               separators=(",", ":"), ensure_ascii=False).encode("utf-8")
        body_hash = hashlib.sha256(canonical).hexdigest()
        return await self.store.execute(request.requestId, body_hash,
                                        lambda: self._generate(request), uses_model=bool(request.candidates))

    async def _generate(self, request: LinkRequest) -> Outcome:
        if not request.candidates:
            return self._response(request, "NO_MATCH")
        try:
            async with asyncio.timeout(self.timeout_seconds):
                raw = await self.model.suggest(request)
        except ModelCallError as exc:
            return exc.failure
        except TimeoutError:
            return Failure(503, "OUTCOME_UNKNOWN", False)
        return self._validate(request, raw)

    def _validate(self, request: LinkRequest, raw: object) -> LinkResponse:
        try:
            result = ModelResult.model_validate(raw)
        except (ValidationError, ValueError):
            return self._response(request, "REVIEW")
        if result.decision != "SUGGEST":
            return self._response(request, result.decision if not result.links else "REVIEW")
        if not result.links:
            return self._response(request, "REVIEW")
        candidate_ids = {candidate.id for candidate in request.candidates}
        used_visits = set()
        used_spans = set()
        spans = []
        for link in result.links:
            start = request.text.find(link.evidenceQuote)
            if (link.visitId not in candidate_ids or start < 0
                    or request.text.find(link.evidenceQuote, start + 1) >= 0
                    or link.visitId in used_visits):
                return self._response(request, "REVIEW")
            end = start + len(link.evidenceQuote)
            if (start, end) in used_spans:
                return self._response(request, "REVIEW")
            used_visits.add(link.visitId)
            used_spans.add((start, end))
            spans.append(LinkSpan(visitId=link.visitId, startCp=start, endCp=end))
        return self._response(request, "SUGGEST", tuple(spans))

    @staticmethod
    def _response(request: LinkRequest, decision, links=()) -> LinkResponse:
        return LinkResponse(requestId=request.requestId, noteRevision=request.noteRevision,
                            userRevision=request.userRevision, contextHash=request.contextHash,
                            decision=decision, links=links)

    async def aclose(self) -> None:
        await self.store.aclose()
        await self.model.aclose()
