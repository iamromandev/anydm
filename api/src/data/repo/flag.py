from src.data.db.model import AppFlag


class AppFlagDatabaseRepo:
    async def is_set(self, name: str) -> bool:
        return await AppFlag.exists(name=name)

    async def mark(self, name: str) -> None:
        await AppFlag.get_or_create(name=name)
