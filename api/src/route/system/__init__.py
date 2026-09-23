from fastapi import APIRouter

from .system import router as _system_router

_subrouters = [
    _system_router,
]

router = APIRouter(tags=["System"])

for subrouter in _subrouters:
    router.include_router(subrouter)
