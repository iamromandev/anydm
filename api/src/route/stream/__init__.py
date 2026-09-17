from fastapi import APIRouter

from .stream import router as _stream_router

router = APIRouter(tags=["Stream"])
router.include_router(_stream_router)
