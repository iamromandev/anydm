from fastapi import APIRouter

from .settings import router as _settings_router

_subrouters = [
    _settings_router,
]

router = APIRouter(tags=["Settings"])

for subrouter in _subrouters:
    router.include_router(subrouter)
