from fastapi import APIRouter

from .download import router as _download_router
from .extract import router as _extract_router
from .health import router as _health_router

_subrouters = [
    _health_router,
    _extract_router,
    _download_router,
]

router = APIRouter()

for subrouter in _subrouters:
    router.include_router(subrouter)
