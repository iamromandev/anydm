from fastapi import APIRouter

from .category import router as _category_router

_subrouters = [
    _category_router,
]

router = APIRouter(tags=["Category"])

for subrouter in _subrouters:
    router.include_router(subrouter)
