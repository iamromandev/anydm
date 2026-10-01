from fastapi import APIRouter

from .source import router as _source_router

_subrouters = [
    _source_router,
]

router = APIRouter(tags=["Source"])

for subrouter in _subrouters:
    router.include_router(subrouter)
