"""Bounded in-memory JPEG decoding, metadata removal and idempotent analysis."""

import asyncio
import base64
import binascii
import hashlib
import json
import warnings
from io import BytesIO
from typing import Protocol

from PIL import Image, UnidentifiedImageError
from pydantic import ValidationError

from ..idempotency_store import IdempotencyStore
from ..schemas import Failure, ModelCallError
from .schemas import (ANALYSIS_VERSION, MAX_IMAGE_BYTES, MAX_IMAGE_EDGE, ModelPhotoDescription,
                      PhotoDescriptionRequest, PhotoDescriptionResponse)


class PhotoModel(Protocol):
    async def describe(self, image_jpeg: bytes, *, request_id: str) -> object: ...
    async def aclose(self) -> None: ...


def prepare_image(request: PhotoDescriptionRequest) -> bytes:
    """Only newly encoded pixels leave this boundary; no metadata/disk/file APIs."""
    try:
        raw = base64.b64decode(request.imageBase64, validate=True)
    except (ValueError, binascii.Error):
        raise ModelCallError(Failure(422, "INVALID_IMAGE", False)) from None
    if not raw or len(raw) > MAX_IMAGE_BYTES:
        raise ModelCallError(Failure(413, "IMAGE_TOO_LARGE", False))
    if hashlib.sha256(raw).hexdigest() != request.imageSha256.lower():
        raise ModelCallError(Failure(422, "IMAGE_HASH_MISMATCH", False))
    if not raw.startswith(b"\xff\xd8") or not raw.endswith(b"\xff\xd9"):
        raise ModelCallError(Failure(422, "INVALID_IMAGE", False))
    try:
        with warnings.catch_warnings():
            warnings.simplefilter("error")
            with Image.open(BytesIO(raw), formats=["JPEG"]) as source:
                if source.format != "JPEG" or getattr(source, "n_frames", 1) != 1:
                    raise ValueError("single JPEG required")
                if min(source.size) < 1 or max(source.size) > MAX_IMAGE_EDGE:
                    raise ModelCallError(Failure(413, "IMAGE_DIMENSIONS_TOO_LARGE", False))
                source.load()
                # A new canvas deliberately has no EXIF/ICC/XMP/COM or source info.
                with Image.new("RGB", source.size) as pixels:
                    with source.convert("RGB") as converted:
                        pixels.paste(converted)
                    for quality in (85, 70, 55, 40):
                        with BytesIO() as target:
                            pixels.save(target, format="JPEG", quality=quality, subsampling=2,
                                        optimize=False, progressive=False)
                            encoded = target.getvalue()
                        if len(encoded) <= MAX_IMAGE_BYTES:
                            return encoded
    except ModelCallError:
        raise
    except (UnidentifiedImageError, Image.DecompressionBombError, OSError, ValueError, SyntaxError, Warning):
        raise ModelCallError(Failure(422, "INVALID_IMAGE", False)) from None
    raise ModelCallError(Failure(413, "IMAGE_TOO_LARGE", False))


class PhotoDescriptionService:
    def __init__(self, model: PhotoModel, *, model_id: str, store: IdempotencyStore | None = None,
                 timeout_seconds: float = 30):
        if not isinstance(model_id, str) or not model_id.strip() or not 0 < timeout_seconds <= 30:
            raise ValueError("invalid photo service configuration")
        self.model = model
        self.model_id = model_id
        self.store = store if store is not None else IdempotencyStore()
        self.timeout_seconds = timeout_seconds

    async def describe(self, request: PhotoDescriptionRequest) -> PhotoDescriptionResponse | Failure:
        canonical = json.dumps({"model": self.model_id, "analysisVersion": ANALYSIS_VERSION,
                                "request": request.model_dump(mode="json")}, sort_keys=True,
                               separators=(",", ":"), ensure_ascii=False).encode("utf-8")
        body_hash = hashlib.sha256(canonical).hexdigest()
        # >128 characters cannot collide with a v1 requestId in a shared store.
        key = "v2/photo:" + hashlib.sha512(request.requestId.encode("utf-8")).hexdigest()
        return await self.store.execute(key, body_hash, lambda: self._generate(request), uses_model=True)

    async def _generate(self, request: PhotoDescriptionRequest) -> PhotoDescriptionResponse | Failure:
        try:
            async with asyncio.timeout(self.timeout_seconds):
                image_jpeg = prepare_image(request)
                raw = await self.model.describe(image_jpeg, request_id=request.requestId)
        except ModelCallError as exc:
            return exc.failure
        except TimeoutError:
            return Failure(503, "OUTCOME_UNKNOWN", False)
        try:
            description = ModelPhotoDescription.model_validate(raw)
        except (ValidationError, ValueError):
            return Failure(502, "INVALID_MODEL_RESPONSE", False)
        return PhotoDescriptionResponse(requestId=request.requestId, imageSha256=request.imageSha256,
                                        analysisVersion=ANALYSIS_VERSION, description=description.description,
                                        labels=tuple(description.labels))

    async def aclose(self) -> None:
        await self.store.aclose()
        await self.model.aclose()
