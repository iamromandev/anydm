from fastapi import APIRouter

from .search import router as _search_router

_subrouters = [
    _search_router,
]

router = APIRouter(tags=["Search"])

for subrouter in _subrouters:
    router.include_router(subrouter)
