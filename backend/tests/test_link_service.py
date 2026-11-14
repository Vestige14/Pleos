from copy import deepcopy

import pytest
from pydantic import ValidationError

from tests.support import FakeModel, payload, suggestion


def request(**changes):
    from app.schemas import LinkRequest
    return LinkRequest.model_validate(payload(**changes))


async def test_explicit_restaurant_is_not_forced_to_latest_cafe():
    # A service that chooses the latest visit instead of validating the evidence breaks this.
    from app.link_service import LinkService
    model = FakeModel()
    service = LinkService(model)
    result = await service.link(request())
    assert result.model_dump(mode="json") == {
        "requestId": "job-01", "noteRevision": 1, "userRevision": 0,
        "contextHash": "snapshot-01", "decision": "SUGGEST",
        "links": [{"visitId": "v1", "startCp": 3, "endCp": 15}],
    }
    await service.aclose()


async def test_multiple_places_use_separate_exact_spans():
    from app.link_service import LinkService
    model = FakeModel({"decision": "SUGGEST", "links": [
        {"visitId": "v1", "evidenceQuote": "식당은 좋았는데"},
        {"visitId": "v3", "evidenceQuote": "카페는 시끄러웠어"},
    ]})
    service = LinkService(model)
    result = await service.link(request(text="식당은 좋았는데 카페는 시끄러웠어"))
    assert [link.model_dump() for link in result.links] == [
        {"visitId": "v1", "startCp": 0, "endCp": 8},
        {"visitId": "v3", "startCp": 9, "endCp": 18},
    ]
    await service.aclose()


async def test_emoji_indices_count_unicode_codepoints_not_utf16():
    from app.link_service import LinkService
    service = LinkService(FakeModel(suggestion(quote="칼국수집")))
    result = await service.link(request(text="😀👍 칼국수집!"))
    assert result.links[0].model_dump() == {"visitId": "v1", "startCp": 3, "endCp": 7}
    await service.aclose()


@pytest.mark.parametrize("model_result", [
    {"decision": "REVIEW", "links": []},
    None,
    {"decision": "SUGGEST", "links": []},
    suggestion("outside"),
    suggestion(quote="존재하지 않는 말"),
    suggestion(quote=""),
    {"decision": "SUGGEST", "links": [suggestion()["links"][0]] * 2},
    {"decision": "SUGGEST", "links": suggestion()["links"], "requestId": "forged"},
    {"decision": "NO_MATCH", "links": suggestion()["links"]},
    {"decision": "SUGGEST", "links": [{"visitId": "v1", "evidenceQuote": "칼국수집", "confidence": 1}]},
    {"decision": "SUGGEST", "links": suggestion()["links"] * 11},
    {"decision": "SUGGEST", "links": "not-an-array"},
])
async def test_ambiguous_refused_or_invalid_results_require_review(model_result):
    from app.link_service import LinkService
    model = FakeModel()
    model.result = model_result
    service = LinkService(model)
    result = await service.link(request())
    assert result.decision == "REVIEW"
    assert result.links == ()
    await service.aclose()


@pytest.mark.parametrize("text, quote", [("칼국수집 그리고 칼국수집", "칼국수집"), ("aaaa", "aa")])
async def test_repeated_including_overlapping_quote_requires_review(text, quote):
    from app.link_service import LinkService
    service = LinkService(FakeModel(suggestion(quote=quote)))
    result = await service.link(request(text=text))
    assert result.decision == "REVIEW"
    assert result.links == ()
    await service.aclose()


async def test_no_candidates_is_no_match_without_external_call():
    from app.link_service import LinkService
    model = FakeModel()
    service = LinkService(model)
    result = await service.link(request(candidates=[]))
    assert result.decision == "NO_MATCH"
    assert result.links == ()
    assert model.calls == 0
    await service.aclose()


@pytest.mark.parametrize("change", [
    {"text": ""}, {"text": "  \n"}, {"text": "가" * 4001}, {"text": "\ud800"},
    {"noteRevision": True}, {"noteRevision": -1}, {"userRevision": -1}, {"userRevision": "1"},
    {"requestId": ""}, {"contextHash": " "}, {"requestId": "a" * 129},
    {"capturedAt": "2026-11-08T15:30:00"}, {"capturedAt": 1794119400},
    {"capturedAt": "2026-11-08"}, {"extra": "private"},
])
def test_invalid_requests_are_rejected_before_model(change):
    with pytest.raises(ValidationError):
        request(**change)


@pytest.mark.parametrize("field,value", [
    ("id", ""), ("placeName", " "), ("placeName", "가" * 201),
    ("arrivedAt", "2026-11-08T12:00:00"), ("arrivedAt", "2026-11-07T12:00:00+09:00"),
    ("arrivedAt", "2026-11-08T16:00:00+09:00"),
    ("leftAt", "2026-11-08T11:00:00+09:00"), ("leftAt", None),
    ("extra", "private"),
])
def test_invalid_candidate_is_rejected(field, value):
    candidates = payload()["candidates"]
    candidates[0][field] = value
    with pytest.raises(ValidationError):
        request(candidates=candidates)


def test_candidate_limit_and_duplicate_ids_are_rejected():
    candidates = payload()["candidates"]
    with pytest.raises(ValidationError):
        request(candidates=[candidates[0]] * 21)
    with pytest.raises(ValidationError):
        request(candidates=[candidates[0], deepcopy(candidates[0])])


def test_seoul_calendar_date_uses_instant_not_offset_local_date():
    candidates = [{"id": "night", "placeName": "야간 방문", "arrivedAt": "2026-11-07T16:00:00Z", "leftAt": "2026-11-08T02:00:00+09:00"}]
    value = request(capturedAt="2026-11-07T17:00:00Z", candidates=candidates)
    assert value.candidates[0].id == "night"


def test_departure_can_cross_midnight_and_input_text_is_not_trimmed():
    candidates = [{"id": "night", "placeName": "야간 방문", "arrivedAt": "2026-11-08T23:00:00+09:00", "leftAt": "2026-11-09T01:00:00+09:00"}]
    value = request(capturedAt="2026-11-08T23:30:00+09:00", candidates=candidates, text="  말 그대로\n")
    assert value.text == "  말 그대로\n"


def test_limits_allow_4000_codepoints_and_20_distinct_candidates():
    candidates = [{**payload()["candidates"][0], "id": f"v{i}"} for i in range(20)]
    assert len(request(text="😀" * 4000, candidates=candidates).text) == 4000


@pytest.mark.parametrize("stamp", ["9999-12-31T23:59:59Z", "0001-01-01T00:00:00+23:00"])
def test_out_of_range_seoul_date_is_rejected_as_validation_error(stamp):
    with pytest.raises(ValidationError):
        request(capturedAt=stamp, candidates=[])


@pytest.mark.parametrize("offset", ["+09:00", "+10:00", "+01:39", "-01:30"])
def test_valid_offset_components_are_preserved(offset):
    stamp = "2026-11-08T12:00:00" + offset
    value = request(capturedAt=stamp, candidates=[])
    assert value.capturedAt.isoformat() == stamp
