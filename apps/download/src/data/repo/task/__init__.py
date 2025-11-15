from collections.abc import AsyncGenerator

from src.data.repo.interface.task import TaskRepo

from .crud import TaskDbRepo


async def get_task_db_repo() -> AsyncGenerator[TaskRepo]:
    yield TaskDbRepo()
