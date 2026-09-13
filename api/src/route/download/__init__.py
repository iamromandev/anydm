from fastapi import APIRouter

from .download import router as _download_router

_subrouters = [
    _download_router,
]

router = APIRouter(tags=["Download"])

for subrouter in _subrouters:
    router.include_router(subrouter)
