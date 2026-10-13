from fastapi import APIRouter

from .health import router as _health_router

router = APIRouter()

router.include_router(_health_router)
