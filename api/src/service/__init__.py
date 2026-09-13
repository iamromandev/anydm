from src.data.repo import TaskDatabaseRepo
from src.lib.youtube.client import get_youtube_client
from src.service.download import DownloadService as DownloadService
from src.service.extract import ExtractService as ExtractService
from src.service.health import HealthService as HealthService


def get_health_service() -> HealthService:
    return HealthService()


def get_extract_service() -> ExtractService:
    return ExtractService(client=get_youtube_client())


def get_download_service() -> DownloadService:
    return DownloadService(repo=TaskDatabaseRepo(), client=get_youtube_client())
