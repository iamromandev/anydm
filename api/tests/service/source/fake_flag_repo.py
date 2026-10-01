"""An in-memory flag store, for tests that don't need Postgres."""

from src.data.repo.flag import AppFlagDatabaseRepo


class FakeFlagRepo(AppFlagDatabaseRepo):
    def __init__(self) -> None:
        self.flags: set[str] = set()

    async def is_set(self, name: str) -> bool:
        return name in self.flags

    async def mark(self, name: str) -> None:
        self.flags.add(name)
