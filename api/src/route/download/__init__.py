from fastapi import APIRouter

from .download import router as _download_router
from .torrent import router as _torrent_router

# Order matters and is the whole reason this list exists. The torrent router
# declares fixed segments under /download; the download router declares
# /download/{task_id}. FastAPI matches in order, so the parameterised route
# must come last or it swallows every torrent path.
_subrouters = [
    _torrent_router,
    _download_router,
]

router = APIRouter(tags=["Download"])

for subrouter in _subrouters:
    router.include_router(subrouter)
