import time

# Captured at process start; used by the health endpoint for uptime.
START_TIME: float = time.time()

# Populated once uvicorn has bound its sockets, so the health endpoint can report
# the real listen address (including OS-assigned ports when port=0).
_LISTEN_HOST: str | None = None
_LISTEN_PORT: int | None = None


def set_listen_addr(host: str | None, port: int | None) -> None:
    global _LISTEN_HOST, _LISTEN_PORT
    _LISTEN_HOST = host
    _LISTEN_PORT = port


def get_listen_addr() -> tuple[str | None, int | None]:
    """Return the real bound ``(host, port)`` after uvicorn starts, else ``(None, None)``."""

    return (_LISTEN_HOST, _LISTEN_PORT)


def get_uptime() -> float:
    """Seconds elapsed since the process started."""

    return time.time() - START_TIME


def format_uptime(seconds: float) -> str:
    """Format a duration in seconds as a compact, human-readable string."""

    seconds = int(seconds)
    days, seconds = divmod(seconds, 86400)
    hours, seconds = divmod(seconds, 3600)
    minutes, seconds = divmod(seconds, 60)

    parts: list[str] = []
    if days:
        parts.append(f"{days}d")
    if hours:
        parts.append(f"{hours}h")
    if minutes:
        parts.append(f"{minutes}m")
    if seconds or not parts:
        parts.append(f"{seconds}s")
    return " ".join(parts)
