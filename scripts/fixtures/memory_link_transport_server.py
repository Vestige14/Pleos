#!/usr/bin/env python3
"""Coordinator-run, bounded loopback fixture; no credential creation or external model client."""
from __future__ import annotations

import argparse
import asyncio
import contextlib
import hashlib
import json
import logging
import os
from pathlib import Path
import re
import socket
import stat
import sys

HOST = "127.0.0.1"
PORT = 18761
QUOTE = "합성 해변 기록"
CASE = re.compile(r"STAGE56_TRANSPORT_CASE=([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}); 😀 합성 해변 기록")


def emit(phase: str, request_hash: str = "", http_status: int = 0, call_count: int = 0) -> None:
    # Fixed phase/hash/status/count only. Never log bodies, headers, tokens, place names or coordinates.
    print(json.dumps({"phase": phase, "request_hash": request_hash,
                      "http_status": http_status, "call_count": call_count}, separators=(",", ":")), flush=True)


def read_existing_token(repo: Path) -> str:
    path = repo / ".local/verification/stage6-local-session.secret"
    descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    with os.fdopen(descriptor, "rb") as stream:
        metadata = os.fstat(stream.fileno())
        if (not stat.S_ISREG(metadata.st_mode) or metadata.st_uid != os.getuid()
                or metadata.st_mode & 0o077 or not 32 <= metadata.st_size <= 257):
            raise RuntimeError("PRIVATE_EXISTING_SESSION_REQUIRED")
        raw = stream.read(258)
    token = raw.decode("ascii").strip()
    if not re.fullmatch(r"[A-Za-z0-9_-]{32,256}", token):
        raise RuntimeError("PRIVATE_EXISTING_SESSION_REQUIRED")
    return token


def make_app(repo: Path, token: str):
    # The fixture directly imports no OpenAI SDK/client and never calls a from_env factory.
    # Existing app.main transitively imports its reviewed adapter module; supplied service prevents client creation.
    sys.path.insert(0, str(repo / "backend"))
    from app.main import create_app
    from app.link_service import LinkService
    from app.schemas import Failure
    import app
    if Path(app.__file__).resolve() != (repo / "backend/app/__init__.py").resolve():
        raise RuntimeError("CURRENT_ROOT_BACKEND_REQUIRED")

    def is_case(request) -> bool:
        match = CASE.fullmatch(request.text)
        return bool(match and len(request.candidates) == 1
                    and request.candidates[0].id == "stage56-transport-" + match.group(1)
                    and request.candidates[0].placeName == "STAGE56 합성 해변")

    def request_hash(request) -> str:
        return hashlib.sha256(request.requestId.encode("utf-8")).hexdigest()

    class LocalOnlyModel:
        def __init__(self):
            self.calls: dict[str, int] = {}

        async def suggest(self, request):
            digest = request_hash(request)
            self.calls[digest] = self.calls.get(digest, 0) + 1
            emit("FAKE_MODEL", digest, 0, self.calls[digest])
            if is_case(request):
                return {"decision": "SUGGEST", "links": [
                    {"visitId": request.candidates[0].id, "evidenceQuote": QUOTE}]}
            return {"decision": "REVIEW", "links": []}

        async def aclose(self):
            emit("FAKE_MODEL_CLOSED")

    model = LocalOnlyModel()

    class TransientOnceService(LinkService):
        def __init__(self):
            super().__init__(model)
            self.seen: set[str] = set()
            self.calls: dict[str, int] = {}

        async def link(self, request):
            # Called only AFTER production create_app authentication, media/body and schema checks.
            digest = request_hash(request)
            self.calls[digest] = self.calls.get(digest, 0) + 1
            if is_case(request) and digest not in self.seen:
                self.seen.add(digest)
                emit("FIXTURE_RETRYABLE_503", digest, 503, self.calls[digest])
                # Fixture-only transient ingress failure BEFORE the unchanged LinkService cache/model.
                # No backend cache clock is forged when Android advances its injected queue clock.
                return Failure(503, "UPSTREAM_UNAVAILABLE", True, retry_after=1)
            result = await super().link(request)
            status = result.status if isinstance(result, Failure) else 200
            emit("BACKEND_RESULT", digest, status, self.calls[digest])
            return result

    return create_app(session_token=token, service=TransientOnceService())


async def serve(app, listener: socket.socket, duration: int) -> None:
    import uvicorn
    config = uvicorn.Config(app, host=HOST, port=PORT, workers=1, access_log=False,
                            log_config=None, log_level="critical", lifespan="on",
                            proxy_headers=False, server_header=False, date_header=False,
                            timeout_graceful_shutdown=10)
    server = uvicorn.Server(config)

    async def expire():
        await asyncio.sleep(duration)
        emit("FIXTURE_DEADLINE")
        server.should_exit = True

    timer = asyncio.create_task(expire())
    emit("FIXTURE_STARTING")
    try:
        await server.serve(sockets=[listener])
        if not server.started:
            raise RuntimeError("FIXTURE_STARTUP_FAILED")
    finally:
        timer.cancel()
        with contextlib.suppress(asyncio.CancelledError):
            await timer
        emit("FIXTURE_STOPPED")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo-root", type=Path, required=True)
    parser.add_argument("--max-seconds", type=int, default=300)
    args = parser.parse_args()
    if not 60 <= args.max_seconds <= 600:
        raise SystemExit("BOUNDED_DURATION_REQUIRED")
    repo = args.repo_root.resolve()
    if not (repo / "backend/app/main.py").is_file():
        raise SystemExit("CURRENT_ROOT_BACKEND_REQUIRED")
    # Child-process environment only; actual API, logging, session and model variables are not inherited by app imports.
    os.environ.clear()
    os.environ.update({"PATH": "/usr/bin:/bin:/usr/sbin:/sbin", "PYTHONDONTWRITEBYTECODE": "1"})
    logging.disable(logging.CRITICAL)
    listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    try:
        # No reuse-port, no port fallback, no killing or replacing a process already on 18761.
        try:
            listener.bind((HOST, PORT))
        except OSError:
            raise SystemExit("LOCAL_PORT_18761_UNAVAILABLE") from None
        listener.listen(128)
        listener.setblocking(False)
        token = read_existing_token(repo)
        app = make_app(repo, token)
        del token
        asyncio.run(serve(app, listener, args.max_seconds))
    except KeyboardInterrupt:
        emit("FIXTURE_INTERRUPTED")
    except SystemExit:
        raise
    except Exception:
        # Fixed error only; no traceback, input, token or exception text can escape.
        raise SystemExit("FIXTURE_CONFIGURATION_OR_RUNTIME_FAILED") from None
    finally:
        listener.close()


if __name__ == "__main__":
    main()
