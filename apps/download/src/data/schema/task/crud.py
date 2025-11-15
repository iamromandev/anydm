from tortoise.contrib.pydantic import pydantic_model_creator

from src.data.model import Task

_TaskCreateSchema = pydantic_model_creator(
    Task, name="TaskCreate", exclude_readonly=True
)


class TaskCreateSchema(_TaskCreateSchema):
    pass


_TaskUpdateSchema = pydantic_model_creator(
    Task, name="TaskUpdate", exclude_readonly=True
)


class TaskUpdateSchema(_TaskUpdateSchema):
    pass


_TaskOutSchema = pydantic_model_creator(
    Task, name="TaskOut"
)


class TaskOutSchema(_TaskOutSchema):
    pass
