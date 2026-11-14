import asyncio
from copy import deepcopy


def payload(**changes):
    value = {
        "requestId": "job-01", "noteRevision": 1, "userRevision": 0,
        "contextHash": "snapshot-01", "capturedAt": "2026-11-08T15:30:00+09:00",
        "text": "아까 칼국수집 국물이 좋았어",
        "candidates": [
            {"id": "v1", "placeName": "대부도 칼국수집", "arrivedAt": "2026-11-08T12:00:00+09:00", "leftAt": "2026-11-08T13:10:00+09:00"},
            {"id": "v2", "placeName": "방아머리해변", "arrivedAt": "2026-11-08T13:30:00+09:00", "leftAt": "2026-11-08T14:50:00+09:00"},
            {"id": "v3", "placeName": "바다 앞 카페", "arrivedAt": "2026-11-08T15:10:00+09:00", "leftAt": "2026-11-08T16:00:00+09:00"},
        ],
    }
    value.update(deepcopy(changes))
    return value


def suggestion(visit="v1", quote="칼국수집 국물이 좋았어"):
    return {"decision": "SUGGEST", "links": [{"visitId": visit, "evidenceQuote": quote}]}


class FakeModel:
    def __init__(self, result=None):
        self.result = result if result is not None else suggestion()
        self.calls = 0
        self.closed = False
        self.started = asyncio.Event()
        self.release = None

    async def suggest(self, request):
        self.calls += 1
        self.started.set()
        if self.release is not None:
            await self.release.wait()
        if isinstance(self.result, BaseException):
            raise self.result
        return self.result

    async def aclose(self):
        self.closed = True
