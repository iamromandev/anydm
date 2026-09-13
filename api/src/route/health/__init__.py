from fastapi import APIRouter

from .health import router as _health_router

_subrouters = [
    _health_router,
]

router = APIRouter(prefix="/health", tags=["Health"])

for subrouter in _subrouters:
    router.include_router(subrouter)
