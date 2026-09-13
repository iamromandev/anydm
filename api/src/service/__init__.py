from src.lib.youtube.client import get_youtube_client
from src.service.extract import ExtractService as ExtractService
from src.service.health import HealthService as HealthService


def get_health_service() -> HealthService:
    return HealthService()


def get_extract_service() -> ExtractService:
    return ExtractService(client=get_youtube_client())
