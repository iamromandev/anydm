from src.service.health import HealthService as HealthService


def get_health_service() -> HealthService:
    return HealthService()
