from fastapi import APIRouter

from .extract import router as _extract_router

_subrouters = [
    _extract_router,
]

router = APIRouter(tags=["Extract"])

for subrouter in _subrouters:
    router.include_router(subrouter)
