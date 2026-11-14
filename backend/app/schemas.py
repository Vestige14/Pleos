"""Strict request/response boundaries; no input values in validation errors."""

import re
from dataclasses import dataclass
from datetime import datetime
from typing import Annotated, Literal
from zoneinfo import ZoneInfo

from pydantic import AfterValidator, BaseModel, BeforeValidator, ConfigDict, Field, model_validator

SEOUL = ZoneInfo("Asia/Seoul")
MAX_BODY_BYTES = 65_536


def valid_text(value: str) -> str:
    if not value.strip() or any(0xD800 <= ord(char) <= 0xDFFF for char in value):
        raise ValueError("invalid text")
    return value


def offset_timestamp(value: object) -> datetime:
    if not isinstance(value, str) or not re.fullmatch(
        r"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\.[0-9]{1,6})?(?:Z|[+-](?:[01][0-9]|2[0-3]):[0-5][0-9])", value
    ):
        raise ValueError("offset timestamp required")
    try:
        parsed = datetime.fromisoformat(value)
    except ValueError:
        raise ValueError("invalid timestamp") from None
    return parsed


Identifier = Annotated[str, Field(min_length=1, max_length=128), AfterValidator(valid_text)]
PlaceName = Annotated[str, Field(min_length=1, max_length=200), AfterValidator(valid_text)]
OriginalText = Annotated[str, Field(min_length=1, max_length=4000), AfterValidator(valid_text)]
Timestamp = Annotated[datetime, BeforeValidator(offset_timestamp)]
Revision = Annotated[int, Field(ge=0, le=9_223_372_036_854_775_807)]
Decision = Literal["SUGGEST", "REVIEW", "NO_MATCH"]


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True, frozen=True, hide_input_in_errors=True)


class Candidate(StrictModel):
    id: Identifier
    placeName: PlaceName
    arrivedAt: Timestamp
    leftAt: Timestamp

    @model_validator(mode="after")
    def valid_interval(self):
        if self.leftAt < self.arrivedAt:
            raise ValueError("invalid interval")
        return self


class LinkRequest(StrictModel):
    requestId: Identifier
    noteRevision: Revision
    userRevision: Revision
    contextHash: Identifier
    capturedAt: Timestamp
    text: OriginalText
    candidates: Annotated[list[Candidate], Field(max_length=20)]

    @model_validator(mode="after")
    def valid_candidates(self):
        ids = [candidate.id for candidate in self.candidates]
        if len(ids) != len(set(ids)):
            raise ValueError("duplicate candidate")
        try:
            captured_date = self.capturedAt.astimezone(SEOUL).date()
            for candidate in self.candidates:
                if candidate.arrivedAt > self.capturedAt or candidate.arrivedAt.astimezone(SEOUL).date() != captured_date:
                    raise ValueError("invalid candidate context")
        except OverflowError:
            raise ValueError("timestamp outside supported calendar") from None
        return self


class ModelLink(StrictModel):
    visitId: Identifier
    evidenceQuote: OriginalText


class ModelResult(StrictModel):
    decision: Decision
    links: Annotated[list[ModelLink], Field(max_length=10)]


class LinkSpan(StrictModel):
    visitId: Identifier
    startCp: Annotated[int, Field(ge=0)]
    endCp: Annotated[int, Field(ge=1)]


class LinkResponse(StrictModel):
    requestId: Identifier
    noteRevision: Revision
    userRevision: Revision
    contextHash: Identifier
    decision: Decision
    links: tuple[LinkSpan, ...]


# Only these sanitized values may enter the idempotency cache; never exceptions/tracebacks.
@dataclass(frozen=True, slots=True)
class Failure:
    status: int
    code: str
    retryable: bool
    retry_after: int | None = None


class ModelCallError(Exception):
    def __init__(self, failure: Failure):
        super().__init__(failure.code)
        self.failure = failure


def decode_json(text: str) -> object:
    """Reject non-JSON constants and duplicate keys instead of silently choosing a value."""
    import json

    def unique_object(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError("duplicate JSON key")
            result[key] = value
        return result

    def invalid_constant(_value):
        raise ValueError("invalid JSON constant")

    return json.loads(text, object_pairs_hook=unique_object, parse_constant=invalid_constant)
