import uuid
from typing import Any

from src.core.base import BaseRepo
from src.data.model import Task
from src.data.repo.interface.task import TaskRepo
from src.data.schema.task import TaskCreateSchema, TaskOutSchema, TaskUpdateSchema


class TaskDbRepo(BaseRepo[Task], TaskRepo):
    def __init__(self) -> None:
        super().__init__(Task)

    async def create(self, data: TaskCreateSchema) -> TaskOutSchema:
        db_task: Task = await super().create(**data.model_dump(exclude_unset=True))
        return await TaskOutSchema.from_tortoise_orm(db_task)

    async def update(self, target: uuid.UUID | Task, data: TaskUpdateSchema) -> TaskOutSchema:
        db_task: Task = await super().update(target=target, **data.model_dump(exclude_unset=True))
        return await TaskOutSchema.from_tortoise_orm(db_task)

    async def get_by_input(self, key: str, value: Any) -> TaskOutSchema | None:
        sql = f"""
            SELECT *
            FROM task
            WHERE JSON_UNQUOTE(JSON_EXTRACT(input, '$.{key}')) = '{value}'
            LIMIT 1;
        """
        db_task: Task | None = await super().first_by_raw(sql)
        return await TaskOutSchema.from_tortoise_orm(db_task) if db_task else None
