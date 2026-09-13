from src.config import get_settings
from src.core.base import BaseService
from src.core.common import get_app_version
from src.core.runtime import format_uptime, get_listen_addr, get_uptime
from src.core.type import Status
from src.data.db import get_db_health, get_db_version
from src.data.schema.health import DatabaseSchema, HealthSchema


class HealthService(BaseService):
    def __init__(self) -> None:
        super().__init__()

    async def check_health(
        self,
        host: str | None = None,
        port: int | None = None,
    ) -> HealthSchema:
        app_version = get_app_version()
        settings = get_settings()
        # Prefer the address reported by the incoming request; fall back to any
        # address captured at startup (e.g. when called outside a request).
        if host is None or port is None:
            fallback_host, fallback_port = get_listen_addr()
            host = host or fallback_host
            port = port or fallback_port
        db_status = Status.SUCCESS if await get_db_health() else Status.ERROR
        db_version = await get_db_version()
        db_schema = DatabaseSchema(status=db_status, version=db_version)

        health: HealthSchema = HealthSchema(
            version=app_version,
            host=host,
            port=port,
            environment=settings.env.value,
            uptime=format_uptime(get_uptime()),
            db=db_schema,
        )
        health.log()
        return health
