import time
from collections.abc import Awaitable, Callable

from fastapi import FastAPI, Request, Response

from src.core.format import format_duration


def init_process_time_tracing(app: FastAPI) -> None:
    @app.middleware("http")
    async def add_process_time_header(
        request: Request,
        call_next: Callable[[Request], Awaitable[Response]]
    ) -> Response:
        start_at: float = time.perf_counter()
        response: Response = await call_next(request)
        elapsed: float = time.perf_counter() - start_at  # seconds

        response.headers["X-Process-Time"] = format_duration(elapsed)
        return response
