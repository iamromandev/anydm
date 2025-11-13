from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from src.core.common import get_app_version
from src.core.config import settings
from src.core.error import init_global_errors
from src.core.middleware import init_process_time_tracing
from src.data import init_db, run_migrations
from src.route.download import router as _download_router

# import routers
from src.route.health import router as _health_router


@asynccontextmanager
async def lifespan(fa: FastAPI):
    await run_migrations()
    yield  # startup complete
    # any shutdown code here


app = FastAPI(
    title="Download Application",
    version=get_app_version(),
    debug=settings.debug,
    lifespan=lifespan
)

app.add_middleware(
    CORSMiddleware,
    allow_credentials=True,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

init_global_errors(app)
init_db(app)
init_process_time_tracing(app)

routers = [
    _health_router,
    _download_router
]
for router in routers:
    app.include_router(router)
