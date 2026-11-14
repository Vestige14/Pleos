"""Resolve only explicit supported calendar evidence against the recording clock."""

import re
from dataclasses import dataclass
from datetime import date, datetime, timedelta

from ..schemas import SEOUL


@dataclass(frozen=True, slots=True)
class ResolvedTime:
    mode: str
    event_date: date | None


UNRESOLVED = ResolvedTime("UNRESOLVED", None)
_ISO_DATE = re.compile(r"(?<![0-9])([0-9]{4})-([0-9]{1,2})-([0-9]{1,2})(?![0-9])")
_KOREAN_DATE = re.compile(
    r"(?<![0-9])(?:([0-9]{4})\s*년\s*)?([0-9]{1,2})\s*월\s*([0-9]{1,2})\s*일")
_SHORT_DATE = re.compile(r"(?<![0-9/])([0-9]{1,2})/([0-9]{1,2})(?![0-9/])")
_RELATIVE = re.compile(r"엊그제|그저께|그제|어제|오늘|아까")
_AMBIGUOUS = re.compile(
    r"예전|지난번|언젠가|옛날|그때|이전|며칠\s*전|몇\s*(?:년|달|일)\s*전|얼마\s*전|"
    r"지난\s*(?:주|달|해|봄|여름|가을|겨울)|작년|재작년|내일|모레|나중|다음\s*(?:주|달|해)")


def seoul_date(value: datetime) -> date:
    return value.astimezone(SEOUL).date()


def _recent_date(month: int, day: int, captured_date: date) -> date:
    # Up to eight years also covers a Feb 29 across a non-leap century.
    for year in range(captured_date.year, max(0, captured_date.year - 8), -1):
        try:
            value = date(year, month, day)
        except ValueError:
            continue
        if value <= captured_date:
            return value
    raise ValueError("invalid or unsupported calendar date")


def resolve_time(text: str, captured_at: datetime) -> ResolvedTime:
    """No server-now clock, model, normalization or truncation participates here."""
    try:
        captured_date = seoul_date(captured_at)
        if _AMBIGUOUS.search(text):
            return UNRESOLVED
        dates: set[date] = set()
        explicit = False
        for match in _ISO_DATE.finditer(text):
            dates.add(date(*(int(part) for part in match.groups())))
            explicit = True
        for match in _KOREAN_DATE.finditer(text):
            year, month, day = match.groups()
            dates.add(date(int(year), int(month), int(day)) if year is not None
                      else _recent_date(int(month), int(day), captured_date))
            explicit = True
        for match in _SHORT_DATE.finditer(text):
            month, day = (int(part) for part in match.groups())
            dates.add(_recent_date(month, day, captured_date))
            explicit = True
        for match in _RELATIVE.finditer(text):
            delta = {"오늘": 0, "아까": 0, "어제": 1, "그제": 2, "엊그제": 2, "그저께": 2}[match[0]]
            dates.add(captured_date - timedelta(days=delta))
            explicit = explicit or delta != 0
        if len(dates) > 1:
            return UNRESOLVED
        return ResolvedTime("EXPLICIT_DATE" if explicit else "CAPTURE_DAY",
                            next(iter(dates)) if dates else captured_date)
    except (ValueError, OverflowError):
        return UNRESOLVED
