"""Independent Stage7 acceptance with synthetic records and images only."""
import asyncio
import base64
import hashlib
import json
from datetime import datetime
from io import BytesIO

import httpx
import pytest
from PIL import Image
from pydantic import ValidationError

from app.main import create_app
from app.link_service import LinkService
from app.memory_v2.association_service import MemoryAssociationService
from app.memory_v2.photo_service import PhotoDescriptionService, prepare_image
from app.memory_v2.openai_adapters import OpenAIPhotoAdapter, OpenAIAssociationAdapter
from app.memory_v2.schemas import MemoryLinkRequest, MemoryCandidate, PhotoDescriptionRequest
from app.memory_v2.temporal import resolve_time
from app.schemas import Failure, ModelCallError
from tests.support import FakeModel
from tests.test_openai_adapter import adapter, envelope, request as v1_request


def candidate(**changes):
    value = dict(id="photo:synthetic", kind="PHOTO", evidenceDate="2026-11-01",
        timeBasis="PHOTO_CAPTURE", description="바다와 노을", occurredAt="2026-11-01T13:00:00+09:00",
        observedAt=None, placeName=None, latitude=None, longitude=None)
    value.update(changes)
    return value


def memory(**changes):
    value = dict(requestId="memory-synthetic", noteRevision=1, userRevision=0, contextHash="hash-synthetic",
        capturedAt="2026-11-01T14:00:00+09:00", text="오늘 간 바다 🌊 예쁘더라",
        timeScope=dict(mode="CAPTURE_DAY", eventDate="2026-11-01"), candidates=[candidate()])
    value.update(changes)
    return MemoryLinkRequest.model_validate(value)


class AssociationFake(FakeModel):
    def __init__(self, result=None):
        super().__init__(result or dict(decision="SUGGEST", links=[dict(targetId="photo:synthetic", kind="PHOTO", evidenceQuote="바다 🌊")]))
        self.inputs = []

    async def suggest(self, request):
        self.inputs.append(request)
        return await super().suggest(request)


class PhotoFake:
    def __init__(self):
        self.calls = 0
        self.images = []

    async def describe(self, image_jpeg, *, request_id):
        self.calls += 1
        self.images.append(image_jpeg)
        return dict(description="파란 바다와 노을", labels=["바다", "노을"])

    async def aclose(self):
        pass


def photo_request(size=(48, 32), raw=None, **changes):
    if raw is None:
        exif = Image.Exif()
        exif[270] = "SYNTHETIC_PRIVATE_FILENAME_DATE_GPS_STT"
        exif[306] = "2026:10:09 13:00:00"
        with BytesIO() as out:
            Image.new("RGB", size, "blue").save(out, "JPEG", exif=exif, comment=b"SYNTHETIC_PRIVATE_COMMENT")
            raw = out.getvalue()
    value = dict(requestId="photo-synthetic", imageSha256=hashlib.sha256(raw).hexdigest(), mimeType="image/jpeg",
        imageBase64=base64.b64encode(raw).decode())
    value.update(changes)
    return PhotoDescriptionRequest.model_validate(value)


@pytest.mark.parametrize("text,captured,mode,event", [
    ("오늘 바다", "2026-10-31T15:30:00Z", "CAPTURE_DAY", "2026-11-01"),
    ("아까 바다", "2026-11-01T14:00:00+09:00", "CAPTURE_DAY", "2026-11-01"),
    ("어제 바다", "2026-11-01T14:00:00+09:00", "EXPLICIT_DATE", "2026-10-31"),
    ("그제 바다", "2026-11-01T14:00:00+09:00", "EXPLICIT_DATE", "2026-10-30"),
    ("12/31 바다", "2026-01-01T14:00:00+09:00", "EXPLICIT_DATE", "2025-12-31"),
    ("2월 29일 바다", "2026-11-01T14:00:00+09:00", "EXPLICIT_DATE", "2024-02-29"),
    ("예전에 바다", "2026-11-01T14:00:00+09:00", "UNRESOLVED", None),
    ("10/9와 오늘 바다", "2026-11-01T14:00:00+09:00", "UNRESOLVED", None),
])
def test_recording_clock_temporal_contract(text, captured, mode, event):
    actual = resolve_time(text, datetime.fromisoformat(captured))
    assert actual.mode == mode
    assert (str(actual.event_date) if actual.event_date else None) == event


async def test_oct9_photo_and_nov1_today_never_call_model():
    fake = AssociationFake()
    service = MemoryAssociationService(fake)
    value = memory(candidates=[candidate(evidenceDate="2026-10-09", occurredAt="2026-10-09T13:00:00+09:00")])
    assert (await service.link(value)).decision == "NO_MATCH"
    assert fake.calls == 0
    await service.aclose()


@pytest.mark.parametrize("changes,decision", [
    (dict(candidates=[]), "NO_MATCH"),
    (dict(text="예전에 바다", timeScope=dict(mode="UNRESOLVED", eventDate=None)), "REVIEW"),
    (dict(timeScope=dict(mode="EXPLICIT_DATE", eventDate="2026-10-09")), "REVIEW"),
])
async def test_empty_ambiguous_or_user_date_conflict_is_free(changes, decision):
    fake = AssociationFake()
    service = MemoryAssociationService(fake)
    assert (await service.link(memory(**changes))).decision == decision
    assert fake.calls == 0
    await service.aclose()


def test_note_candidate_uses_complete_own_text_and_recording_clock():
    other = candidate(id="note:old", kind="NOTE", timeBasis="NOTE_EXPERIENCE",
        description="가" * 1500 + " 오늘 바다", evidenceDate="2026-10-09", occurredAt="2026-10-09T22:00:00+09:00")
    assert MemoryCandidate.model_validate(other).evidenceDate.isoformat() == "2026-10-09"
    with pytest.raises(ValidationError):
        MemoryCandidate.model_validate(other | dict(evidenceDate="2026-11-01"))


def test_navigation_observation_provenance_and_undated_recent():
    common = candidate(kind="NAVIGATION", timeBasis="NAV_ARRIVED", occurredAt=None,
        observedAt="2026-10-31T15:30:00Z")
    assert MemoryCandidate.model_validate(common).occurredAt is None
    for invalid in [common | dict(evidenceDate="2026-10-31"),
                    common | dict(timeBasis="UNDATED_RECENT")]:
        with pytest.raises(ValidationError):
            MemoryCandidate.model_validate(invalid)
    assert MemoryCandidate.model_validate(common | dict(timeBasis="UNDATED_RECENT", evidenceDate=None)).evidenceDate is None


async def test_idempotent_suggestion_codepoints_and_changed_body_conflict():
    fake = AssociationFake()
    service = MemoryAssociationService(fake)
    value = memory()
    a, b = await asyncio.gather(service.link(value), service.link(value))
    assert a == b and a.decision == "SUGGEST" and fake.calls == 1
    span = a.links[0]
    assert value.text[span.startCp:span.endCp] == "바다 🌊"
    conflict = await service.link(memory(text="오늘 다른 바다"))
    assert isinstance(conflict, Failure) and conflict.status == 409
    await service.aclose()


@pytest.mark.parametrize("link,text", [
    (dict(targetId="absent", kind="PHOTO", evidenceQuote="바다"), "오늘 바다"),
    (dict(targetId="photo:synthetic", kind="NOTE", evidenceQuote="바다"), "오늘 바다"),
    (dict(targetId="photo:synthetic", kind="PHOTO", evidenceQuote="바다"), "오늘 바다 바다"),
    (dict(targetId="photo:synthetic", kind="PHOTO", evidenceQuote="없는 구절"), "오늘 바다"),
])
async def test_invalid_model_link_is_review(link, text):
    fake = AssociationFake(dict(decision="SUGGEST", links=[link]))
    service = MemoryAssociationService(fake)
    assert (await service.link(memory(text=text))).decision == "REVIEW"
    await service.aclose()


def test_metadata_free_jpeg_and_bounds():
    request = photo_request()
    prepared = prepare_image(request)
    assert b"SYNTHETIC_PRIVATE" not in prepared
    with Image.open(BytesIO(prepared)) as decoded:
        assert not decoded.getexif() and not decoded.info.get("comment")
        assert max(decoded.size) <= 1024
    assert len(prepared) <= 524288
    for value, code in [(photo_request(size=(1025, 1)), "IMAGE_DIMENSIONS_TOO_LARGE"),
                        (photo_request(raw=b"not a jpeg"), "INVALID_IMAGE"),
                        (photo_request(imageSha256="0" * 64), "IMAGE_HASH_MISMATCH")]:
        with pytest.raises(ModelCallError) as failure:
            prepare_image(value)
        assert failure.value.failure.code == code


async def test_photo_exact_route_auth_replay_and_no_context_fields():
    photo = PhotoFake()
    service = PhotoDescriptionService(photo, model_id="synthetic-model")
    association = MemoryAssociationService(AssociationFake())
    token = "synthetic_stage7_test_token_0000000"
    app = create_app(session_token=token, service=LinkService(FakeModel()), photo_service=service, association_service=association)
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client:
        data = photo_request().model_dump(mode="json")
        assert (await client.post("/v2/photo-descriptions", json=data)).status_code == 401
        headers = dict(Authorization="Bearer " + token)
        for bad in [data | dict(text="private STT"), data | dict(filename="private.jpg")]:
            assert (await client.post("/v2/photo-descriptions", json=bad, headers=headers)).status_code == 422
        for _ in range(2):
            response = await client.post("/v2/photo-descriptions", json=data, headers=headers)
            assert response.status_code == 200
            assert set(response.json()) == {"requestId", "imageSha256", "analysisVersion", "description", "labels"}
        assert photo.calls == 1
        response = await client.post("/v2/memory-links", content=b" " * 65537, headers=headers | {"Content-Type": "application/json"})
        assert response.status_code == 413
    await service.aclose()
    await association.aclose()


async def test_shared_transport_isolation_admission_usage_and_unknown(monkeypatch):
    calls, usage = [], []
    def handler(req):
        wire = json.loads(req.content)
        calls.append(wire)
        value = dict(description="바다", labels=["파랑"]) if len(calls) == 1 else dict(decision="REVIEW", links=[])
        return httpx.Response(200, json=envelope(text=json.dumps(value)))
    transport = adapter(monkeypatch, handler)
    async def record(event):
        usage.append(event)
    transport.on_usage = record
    image = prepare_image(photo_request())
    await OpenAIPhotoAdapter(transport).describe(image, request_id="SYNTHETIC_PRIVATE_REQUEST_ID")
    await OpenAIAssociationAdapter(transport).suggest(memory())
    await transport.suggest(v1_request())
    photo_wire = calls[0]
    assert len(photo_wire["input"]) == 2
    content = photo_wire["input"][1]["content"]
    assert len(content) == 1 and content[0]["type"] == "input_image"
    assert base64.b64decode(content[0]["image_url"].split(",", 1)[1]) == image
    assert "SYNTHETIC_PRIVATE" not in json.dumps(photo_wire)
    assert all(w["reasoning"] == dict(effort="low") and w["service_tier"] == "default" and not w["store"] for w in calls)
    assert len(usage) == 3 and all(e.outcome == "RECEIVED" for e in usage)
    async def deny(_):
        return False
    transport.admit_call = deny
    with pytest.raises(ModelCallError) as blocked:
        await OpenAIPhotoAdapter(transport).describe(image, request_id="blocked")
    assert blocked.value.failure.code == "ADMISSION_REJECTED" and len(calls) == 3
    await transport.aclose()
    def uncertain(req):
        raise httpx.ReadTimeout("synthetic", request=req)
    transport = adapter(monkeypatch, uncertain)
    transport.on_usage = record
    with pytest.raises(ModelCallError) as failure:
        await OpenAIAssociationAdapter(transport).suggest(memory())
    assert failure.value.failure.code == "OUTCOME_UNKNOWN" and not failure.value.failure.retryable
    assert usage[-1].outcome == "OUTCOME_UNKNOWN"
    await transport.aclose()
