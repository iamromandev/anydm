from contextlib import asynccontextmanager
from pathlib import Path

import uvicorn
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from loguru import logger

from src.config import get_settings
from src.config.logging import configure_logging
from src.core.common import get_app_version
from src.core.error import init_global_errors
from src.data.db import init_db
from src.data.repo import TaskDatabaseRepo
from src.route import router as _router
from src.service import build_worker_pool


@asynccontextmanager
async def lifespan(_app: FastAPI):
    """Recover orphans, then run the workers for the life of the process.

    Tortoise's own startup runs first — ``register_tortoise`` merges it ahead of
    this one — so the database is reachable here.

    Recovery is unconditional because this process is the only one that runs
    workers: every row still marked in-flight at boot belonged to a process that
    is gone. ``downloaded_bytes`` survives and the ``.part`` files stay on disk,
    so each requeued task resumes from where it stopped rather than starting
    over — which is what makes ``uvicorn --reload`` survivable.
    """
    settings = get_settings()
    Path(settings.downloads_dir).mkdir(parents=True, exist_ok=True)

    recovered = await TaskDatabaseRepo().recover_orphans()
    if recovered:
        logger.warning("lifespan|requeued {} orphaned task(s)", recovered)

    pool = build_worker_pool()
    await pool.start()
    try:
        yield
    finally:
        await pool.stop()


def create_app() -> FastAPI:
    configure_logging()
    settings = get_settings()
    app = FastAPI(
        title="AnyDM API",
        description="Extract and download media from URLs.",
        version=get_app_version(),
        debug=settings.debug,
        lifespan=lifespan,
    )
    app.add_middleware(
        CORSMiddleware,
        allow_origins=settings.origins,
        allow_credentials=True,
        allow_methods=["*"],
        allow_headers=["*"],
    )

    init_global_errors(app)

    _routers = [
        _router
    ]
    for router in _routers:
        app.include_router(router)

    # Must run while building the app: ``register_tortoise`` merges its
    # init/shutdown into ``lifespan``, so calling it from inside is too late.
    init_db(app)

    return app


app = create_app()


def run() -> None:
    uvicorn.run(
        "src.main:app",
        host="0.0.0.0",
        port=8003,
        reload=False,
        loop="uvloop",
    )
