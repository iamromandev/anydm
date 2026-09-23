from fastapi import APIRouter, Depends

from src.core.auth import require_api_key

from .download import router as _download_router
from .extract import router as _extract_router
from .health import router as _health_router
from .settings import router as _settings_router
from .stream import router as _stream_router
from .system import router as _system_router

#: Everything but health, which has to answer a load balancer or a person
#: checking whether the API is up before either of them has a key.
_subrouters = [
    _extract_router,
    _download_router,
    _stream_router,
    _settings_router,
    _system_router,
]

router = APIRouter()

router.include_router(_health_router)
for subrouter in _subrouters:
    router.include_router(subrouter, dependencies=[Depends(require_api_key)])
