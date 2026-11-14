"""Loopback development API; authenticate before touching the request body."""

import asyncio
import hmac
import os
import re
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from pydantic import ValidationError
from starlette.exceptions import HTTPException
from starlette.requests import ClientDisconnect

from .link_service import LinkService
from .openai_adapter import OpenAILinkAdapter
from .schemas import Failure, LinkRequest, MAX_BODY_BYTES, decode_json


class BodyRejected(Exception):
    def __init__(self, failure: Failure):
        self.failure = failure


def error_response(failure: Failure) -> JSONResponse:
    headers = {"Cache-Control": "no-store", "X-Content-Type-Options": "nosniff"}
    if failure.retryable and failure.retry_after is not None:
        headers["Retry-After"] = str(failure.retry_after)
    if failure.status == 401:
        headers["WWW-Authenticate"] = "Bearer"
    return JSONResponse({"error": {"code": failure.code, "retryable": failure.retryable}},
                        status_code=failure.status, headers=headers)


async def read_body(request: Request) -> bytes:
    media_types = request.headers.getlist("content-type")
    encodings = request.headers.getlist("content-encoding")
    if (len(media_types) != 1 or media_types[0].split(";", 1)[0].strip().lower() != "application/json"
            or (encodings and encodings != ["identity"])):
        raise BodyRejected(Failure(415, "UNSUPPORTED_MEDIA_TYPE", False))
    lengths = request.headers.getlist("content-length")
    if lengths:
        if len(lengths) != 1 or not re.fullmatch(r"[0-9]{1,16}", lengths[0]):
            raise BodyRejected(Failure(400, "INVALID_BODY", False))
        if int(lengths[0]) > MAX_BODY_BYTES:
            raise BodyRejected(Failure(413, "BODY_TOO_LARGE", False))
    chunks = []
    size = 0
    async with asyncio.timeout(10):
        async for chunk in request.stream():
            size += len(chunk)
            if size > MAX_BODY_BYTES:
                raise BodyRejected(Failure(413, "BODY_TOO_LARGE", False))
            chunks.append(chunk)
    return b"".join(chunks)


def create_app(*, session_token: str | None = None, service: LinkService | None = None) -> FastAPI:
    token = os.environ.get("MEMORY_LINK_SESSION_TOKEN", "") if session_token is None else session_token
    if not isinstance(token, str) or not re.fullmatch(r"[A-Za-z0-9_-]{32,256}", token):
        raise RuntimeError("a 32..256 character base64url session token is required")
    expected = token.encode("ascii")
    service = service if service is not None else LinkService(OpenAILinkAdapter.from_env())

    @asynccontextmanager
    async def lifespan(_app):
        try:
            yield
        finally:
            await service.aclose()

    app = FastAPI(lifespan=lifespan, debug=False, docs_url=None, redoc_url=None, openapi_url=None)
    app.state.service = service

    @app.exception_handler(RequestValidationError)
    async def invalid_request(_request, _exc):
        # FastAPI's default validation response includes input values; never use it here.
        return error_response(Failure(422, "INVALID_REQUEST", False))

    @app.exception_handler(HTTPException)
    async def http_error(_request, exc):
        code = {404: "NOT_FOUND", 405: "METHOD_NOT_ALLOWED"}.get(exc.status_code, "HTTP_ERROR")
        return error_response(Failure(exc.status_code, code, False))

    @app.post("/v1/memory-links")
    async def memory_links(request: Request):
        authorization = request.headers.getlist("authorization")
        if len(authorization) != 1:
            return error_response(Failure(401, "UNAUTHORIZED", False))
        scheme, separator, credential = authorization[0].partition(" ")
        if (scheme.lower() != "bearer" or not separator
                or not hmac.compare_digest(credential.encode("utf-8"), expected)):
            return error_response(Failure(401, "UNAUTHORIZED", False))
        try:
            body = await read_body(request)
            try:
                data = decode_json(body.decode("utf-8"))
            except (UnicodeError, ValueError, RecursionError):
                return error_response(Failure(400, "INVALID_JSON", False))
            try:
                value = LinkRequest.model_validate(data)
            except ValidationError:
                return error_response(Failure(422, "INVALID_REQUEST", False))
            result = await service.link(value)
            if isinstance(result, Failure):
                return error_response(result)
            return JSONResponse(result.model_dump(mode="json"), headers={
                "Cache-Control": "no-store", "X-Content-Type-Options": "nosniff"})
        except BodyRejected as exc:
            return error_response(exc.failure)
        except (ClientDisconnect, TimeoutError):
            return error_response(Failure(400, "INVALID_BODY", False))
        except Exception:
            # No exception text, body or headers in the response or application logs.
            return error_response(Failure(500, "INTERNAL_ERROR", False))

    return app
