from fastapi import APIRouter

from .stat import router as _stat_router

_subrouters = [
    _stat_router,
]

router = APIRouter(tags=["Stat"])

for subrouter in _subrouters:
    router.include_router(subrouter)
