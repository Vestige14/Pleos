#!/usr/bin/env python3
"""Synthetic-only Stage7 loopback fixture, reusing the v1 regression fixture."""
import argparse, asyncio, importlib.util, logging, os, socket, sys
from io import BytesIO
from pathlib import Path


def make_app(repo, token):
    spec = importlib.util.spec_from_file_location('stage6_fixture', repo / 'scripts/fixtures/memory_link_transport_server.py')
    module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
    old = module.make_app(repo, token)
    from PIL import Image
    from app.main import create_app
    from app.schemas import Failure
    from app.memory_v2.photo_service import PhotoDescriptionService
    from app.memory_v2.association_service import MemoryAssociationService
    class SyntheticPhoto:
        async def describe(self, image_jpeg, *, request_id):
            image = Image.open(BytesIO(image_jpeg)); image.load()
            if image.size != (96, 64) or any(max(p[:2]) > 8 or p[2] < 240 for p in image.convert('RGB').getdata()):
                from app.schemas import ModelCallError
                raise ModelCallError(Failure(422, 'SYNTHETIC_ONLY', False))
            module.emit('STAGE7_SYNTHETIC_PHOTO')
            return {'description': '합성 파란 바다', 'labels': ['파랑']}
        async def aclose(self): pass
    class SyntheticMemory:
        async def suggest(self, request):
            module.emit('STAGE7_SYNTHETIC_ASSOCIATION')
            return {'decision': 'SUGGEST', 'links': [{'targetId': request.candidates[0].id,
                'kind': request.candidates[0].kind, 'evidenceQuote': '합성 바다'}]}
        async def aclose(self): pass
    class OnlySynthetic(MemoryAssociationService):
        async def link(self, request):
            if request.text != 'STAGE7_SYNTHETIC 오늘 합성 바다' or any(c.id != 'photo:synthetic' for c in request.candidates):
                return Failure(422, 'SYNTHETIC_ONLY', False)
            return await super().link(request)
    return create_app(session_token=token, service=old.state.service,
        photo_service=PhotoDescriptionService(SyntheticPhoto(), model_id='stage7-local-fake'),
        association_service=OnlySynthetic(SyntheticMemory()))


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--repo-root', type=Path, required=True); p.add_argument('--session-root', type=Path, required=True)
    p.add_argument('--max-seconds', type=int, default=300); a = p.parse_args()
    assert 60 <= a.max_seconds <= 600
    repo = a.repo_root.resolve(); sys.path.insert(0, str(repo / 'backend'))
    spec = importlib.util.spec_from_file_location('stage6_support', repo / 'scripts/fixtures/memory_link_transport_server.py')
    module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
    token = module.read_existing_token(a.session_root.resolve())
    for key in list(os.environ):
        if key.startswith(('OPENAI_', 'MEMORY_LINK_')) or key.upper().endswith('_PROXY'): os.environ.pop(key)
    logging.disable(logging.CRITICAL)
    app = make_app(repo, token)
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as listener:
        listener.bind(('127.0.0.1', 18761)); listener.listen(128); listener.setblocking(False)
        asyncio.run(module.serve(app, listener, a.max_seconds))


if __name__ == '__main__': main()
