"""Exact v2 wire fields, with the existing strict JSON and scalar boundaries."""

import re
from datetime import date
from typing import Annotated, Literal

from pydantic import AfterValidator, BeforeValidator, Field, model_validator

from ..schemas import Decision, Identifier, OriginalText, PlaceName, Revision, StrictModel, Timestamp, valid_text
from .temporal import resolve_time, seoul_date

MAX_PHOTO_BODY_BYTES = 1_048_576
MAX_IMAGE_BYTES = 524_288
MAX_IMAGE_EDGE = 1024
MAX_IMAGE_BASE64_CHARS = 4 * ((MAX_IMAGE_BYTES + 2) // 3)
ANALYSIS_VERSION = "visual-v1"


def calendar_date(value: object) -> date:
    if not isinstance(value, str) or not re.fullmatch(r"[0-9]{4}-[0-9]{2}-[0-9]{2}", value):
        raise ValueError("calendar date required")
    return date.fromisoformat(value)


CalendarDate = Annotated[date, BeforeValidator(calendar_date)]
VisualDescription = Annotated[str, Field(min_length=1, max_length=500), AfterValidator(valid_text)]
VisualLabel = Annotated[str, Field(min_length=1, max_length=40), AfterValidator(valid_text)]
Kind = Literal["PHOTO", "NOTE", "NAVIGATION", "VISIT"]
TimeBasis = Literal["PHOTO_CAPTURE", "NOTE_EXPERIENCE", "NAV_OBSERVED", "NAV_ARRIVED", "UNDATED_RECENT", "VISIT"]


class PhotoDescriptionRequest(StrictModel):
    requestId: Identifier
    imageSha256: Annotated[str, Field(pattern=r"^[0-9a-fA-F]{64}$")]
    mimeType: Literal["image/jpeg"]
    imageBase64: Annotated[str, Field(min_length=1, max_length=MAX_IMAGE_BASE64_CHARS, repr=False)]


class ModelPhotoDescription(StrictModel):
    description: VisualDescription
    labels: Annotated[list[VisualLabel], Field(max_length=12)]


class PhotoDescriptionResponse(StrictModel):
    requestId: Identifier
    imageSha256: Annotated[str, Field(pattern=r"^[0-9a-fA-F]{64}$")]
    analysisVersion: Literal["visual-v1"]
    description: VisualDescription
    labels: tuple[VisualLabel, ...]


class TimeScope(StrictModel):
    mode: Literal["CAPTURE_DAY", "EXPLICIT_DATE", "UNRESOLVED"]
    eventDate: CalendarDate | None

    @model_validator(mode="after")
    def valid_scope(self):
        if (self.mode == "UNRESOLVED") != (self.eventDate is None):
            raise ValueError("invalid time scope")
        return self


class MemoryCandidate(StrictModel):
    id: Identifier
    kind: Kind
    evidenceDate: CalendarDate | None
    timeBasis: TimeBasis
    description: Annotated[str, Field(min_length=1, max_length=4000, repr=False), AfterValidator(valid_text)]
    occurredAt: Timestamp | None
    observedAt: Timestamp | None
    placeName: PlaceName | None
    latitude: Annotated[float, Field(ge=-90, le=90, allow_inf_nan=False)] | None
    longitude: Annotated[float, Field(ge=-180, le=180, allow_inf_nan=False)] | None

    @model_validator(mode="after")
    def valid_evidence(self):
        bases = {"PHOTO": {"PHOTO_CAPTURE"}, "NOTE": {"NOTE_EXPERIENCE"},
                 "NAVIGATION": {"NAV_OBSERVED", "NAV_ARRIVED", "UNDATED_RECENT"}, "VISIT": {"VISIT"}}
        if self.timeBasis not in bases[self.kind]:
            raise ValueError("kind and evidence basis disagree")
        if self.kind != "NOTE" and len(self.description) > 1000:
            raise ValueError("candidate description too long")
        if (self.latitude is None) != (self.longitude is None):
            raise ValueError("incomplete coordinate pair")
        try:
            if self.timeBasis == "UNDATED_RECENT":
                if self.evidenceDate is not None or self.occurredAt is not None:
                    raise ValueError("recent list is not dated evidence")
            elif self.timeBasis == "NAV_OBSERVED":
                if self.occurredAt is not None:
                    raise ValueError("observation is not a historical event")
                if self.evidenceDate is not None and (
                        self.observedAt is None or seoul_date(self.observedAt) != self.evidenceDate):
                    raise ValueError("invalid observation date")
            elif self.timeBasis == "NAV_ARRIVED" and self.occurredAt is None:
                # The current SDK reports the callback but no event timestamp.
                # This is the observation date, never an invented exact arrival.
                if self.evidenceDate is not None and (
                        self.observedAt is None or seoul_date(self.observedAt) != self.evidenceDate):
                    raise ValueError("invalid arrival observation date")
            elif self.evidenceDate is not None:
                if self.occurredAt is None:
                    raise ValueError("dated evidence requires source time")
                expected = (resolve_time(self.description, self.occurredAt).event_date
                            if self.kind == "NOTE" else seoul_date(self.occurredAt))
                if expected != self.evidenceDate:
                    raise ValueError("invalid evidence date")
        except OverflowError:
            raise ValueError("timestamp outside supported calendar") from None
        return self


class MemoryLinkRequest(StrictModel):
    requestId: Identifier
    noteRevision: Revision
    userRevision: Revision
    contextHash: Identifier
    capturedAt: Timestamp
    text: OriginalText = Field(repr=False)
    timeScope: TimeScope
    candidates: Annotated[list[MemoryCandidate], Field(max_length=20)]

    @model_validator(mode="after")
    def valid_context(self):
        ids = [candidate.id for candidate in self.candidates]
        if len(set(ids)) != len(ids):
            raise ValueError("duplicate candidate")
        if sum(candidate.kind == "PHOTO" for candidate in self.candidates) > 10:
            raise ValueError("too many photo candidates")
        try:
            if self.timeScope.mode == "CAPTURE_DAY" and self.timeScope.eventDate != seoul_date(self.capturedAt):
                raise ValueError("invalid capture day")
            seoul_date(self.capturedAt)
        except OverflowError:
            raise ValueError("timestamp outside supported calendar") from None
        return self


class ModelMemoryLink(StrictModel):
    targetId: Identifier
    kind: Kind
    evidenceQuote: OriginalText


class ModelMemoryResult(StrictModel):
    decision: Decision
    links: Annotated[list[ModelMemoryLink], Field(max_length=10)]


class MemoryLinkSpan(StrictModel):
    targetId: Identifier
    kind: Kind
    startCp: Annotated[int, Field(ge=0)]
    endCp: Annotated[int, Field(ge=1)]


class MemoryLinkResponse(StrictModel):
    requestId: Identifier
    noteRevision: Revision
    userRevision: Revision
    contextHash: Identifier
    decision: Decision
    links: tuple[MemoryLinkSpan, ...]
