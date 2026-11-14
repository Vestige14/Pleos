"""Register v2 paths through the same authenticated, bounded JSON handler as v1."""

from collections.abc import Awaitable, Callable

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

from .association_service import MemoryAssociationService
from .photo_service import PhotoDescriptionService
from .schemas import MAX_PHOTO_BODY_BYTES, MemoryLinkRequest, PhotoDescriptionRequest


def register_routes(app: FastAPI, *, photo_service: PhotoDescriptionService,
                    association_service: MemoryAssociationService,
                    handle_request: Callable[..., Awaitable[JSONResponse]]) -> None:
    @app.post("/v2/photo-descriptions")
    async def photo_descriptions(request: Request):
        return await handle_request(request, PhotoDescriptionRequest, photo_service.describe,
                                    max_body_bytes=MAX_PHOTO_BODY_BYTES)

    @app.post("/v2/memory-links")
    async def memory_links(request: Request):
        return await handle_request(request, MemoryLinkRequest, association_service.link)
