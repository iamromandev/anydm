import uuid
from collections.abc import Iterable, Sequence
from datetime import UTC, datetime
from functools import cached_property
from typing import Annotated, Any, Generic, TypeVar

from fastapi.encoders import jsonable_encoder
from loguru import logger
from pydantic import BaseModel, ConfigDict, Field
from tortoise import fields, models, queryset
from tortoise.exceptions import DoesNotExist

# database - mode + repo
_ModelT = TypeVar("_ModelT", bound=models.Model)


class Base(models.Model):
    id: uuid.UUID = fields.UUIDField(primary_key=True, default=uuid.uuid4)
    created_at: datetime = fields.DatetimeField(auto_now_add=True, db_index=True)
    updated_at: datetime = fields.DatetimeField(auto_now=True, db_index=True)
    deleted_at: datetime | None = fields.DatetimeField(null=True, db_index=True)

    class Meta:
        abstract = True

    async def soft_delete(self) -> None:
        self.deleted_at = datetime.now(UTC)
        return await self.save()

    @classmethod
    async def get_active(cls: type[_ModelT]) -> queryset.QuerySet[_ModelT]:
        return cls.filter(deleted_at__isnull=True)

    @classmethod
    def db_fields(cls, excludes: list[str] | None = None) -> list[str]:
        excludes = excludes or []
        return [f for f in cls._meta.db_fields if f not in excludes]

    @classmethod
    def from_query_result(cls, *args, **kwargs) -> _ModelT:
        """
        Instantiate a model instance from raw query result.
        Args:
            *args: Positional query result values
            **kwargs: Named query result values
        Returns:
            Model instance
        """
        instance = cls.__new__(cls)

        # Assign all fields
        for field in cls._meta.fields_map.values():
            if field.source_field in kwargs:
                setattr(instance, field.model_field_name, kwargs[field.source_field])
            else:
                setattr(instance, field.model_field_name, None)

        # Assign m2m default as empty list
        for key in cls._meta.m2m_fields:
            setattr(instance, key, [])

        # Set internal state
        instance._saved_in_db = True
        instance._fetched_values = set(kwargs.keys())

        return instance


# repo - operation on the database
class BaseRepo(Generic[_ModelT]):
    _model: type[_ModelT]

    def __init__(self, model: type[_ModelT]) -> None:
        self._model = model

    @cached_property
    def _tag(self) -> str:
        return self.__class__.__name__

    async def first_by_raw(self, sql: str) -> _ModelT | None:
        rows = await self._model.raw(sql)  # type hint to satisfy checker
        if not rows:
            return None
        return rows[0]

    async def exists(self, **kwargs: Any) -> bool:
        return await self._model.filter(**kwargs).exists()

    async def get_or_create(self, **kwargs: Any) -> tuple[_ModelT, bool]:
        return await self._model.get_or_create(**kwargs)

    async def create(self, **kwargs: Any) -> _ModelT:
        return await self._model.create(**kwargs)

    async def get_or_none(self, **kwargs: Any) -> _ModelT | None:
        try:
            return await self._model.get(**kwargs)
        except DoesNotExist:
            return None

    async def get_by_id(
        self,
        id: uuid.UUID,
        select_related: str | Sequence[str] | None = None,
        prefetch_related: str | Sequence[str] | None = None,
        annotations: dict[str, Any] | None = None,
    ) -> _ModelT | None:
        query: queryset.QuerySet[_ModelT] = self._model.filter(id=id)

        # Apply select_related (JOINs for foreign keys)
        if select_related:
            if isinstance(select_related, str):
                select_related = [select_related]
            query = query.select_related(*select_related)

        # Apply prefetch_related (for reverse/many-to-many relations)
        if prefetch_related:
            if isinstance(prefetch_related, str):
                prefetch_related = [prefetch_related]
            query = query.prefetch_related(*prefetch_related)

        # Apply annotations (like Count)
        if annotations:
            query = query.annotate(**annotations)

        return await query.first()

    async def all_ids(
        self,
        *args: Any,
        ids: list[uuid.UUID] | None = None,
        field_name_for_ids: str | None = None,
        sort: str | None = None,
        distinct: bool = False,
        **kwargs: Any,
    ) -> list[uuid.UUID]:
        query: queryset.QuerySet[_ModelT] = self._model.filter(*args, **kwargs)

        if ids and field_name_for_ids:
            query = query.filter(**{f"{field_name_for_ids}__in": ids})

        if distinct:
            query = query.distinct()

        if sort:
            order_fields = [field.strip() for field in sort.split(",")]
            query = query.order_by(*order_fields)

        return await query.values_list("id", flat=True)

    async def all(
        self,
        *args: Any,
        ids: list[uuid.UUID] | None = None,
        field_name_for_ids: str | None = None,
        sort: str | None = None,
        select_related: str | Sequence[str] | None = None,
        prefetch_related: str | Sequence[str] | None = None,
        annotations: dict[str, Any] | None = None,
        distinct: bool = False,
        **kwargs: Any
    ) -> list[_ModelT]:
        query: queryset.QuerySet[_ModelT] = self._model.filter(*args, **kwargs)

        # Filter by IDs if provided
        if ids and field_name_for_ids:
            query = query.filter(**{f"{field_name_for_ids}__in": ids})

        # Apply select_related (JOINs)
        if select_related:
            if isinstance(select_related, str):
                select_related = [select_related]
            query = query.select_related(*select_related)

        # Apply prefetch_related (extra queries for reverse relations)
        if prefetch_related:
            if isinstance(prefetch_related, str):
                prefetch_related = [prefetch_related]
            query = query.prefetch_related(*prefetch_related)

        # Apply annotations (like Count)
        if annotations:
            query = query.annotate(**annotations)

        # Apply distinct
        if distinct:
            query = query.distinct()

        # Sorting
        if sort:
            order_fields = [field.strip() for field in sort.split(",")]
            query = query.order_by(*order_fields)

        # Return all results
        return await query

    async def filter(
        self,
        *args: Any,
        ids: list[uuid.UUID] | None = None,
        field_name_for_ids: str | None = None,
        select_related: str | list[str] | None = None,
        prefetch_related: str | list[str] | None = None,
        annotations: dict[str, Any] | None = None,
        sort: str | None = None,
        page: int = 1,
        page_size: int = 10,
        **kwargs: Any
    ) -> tuple[list[_ModelT], dict[str, int]]:
        query: queryset.QuerySet[_ModelT] = self._model.filter(*args, **kwargs)

        if ids and field_name_for_ids:
            query = query.filter(**{f"{field_name_for_ids}__in": ids})

        if select_related:
            if isinstance(select_related, str):
                select_related = [select_related]
            query = query.select_related(*select_related)

        if prefetch_related:
            if isinstance(prefetch_related, str):
                prefetch_related = [prefetch_related]
            query = query.prefetch_related(*prefetch_related)

        if annotations:
            query = query.annotate(**annotations)

        if sort:
            order_fields = [field.strip() for field in sort.split(",")]
            query = query.order_by(*order_fields)

        total: int = await query.count()
        offset: int = (page - 1) * page_size
        results: list[_ModelT] = await query.offset(offset).limit(page_size)

        meta: dict[str, int] = {
            "page": page,
            "page_size": page_size,
            "total": total,
            "total_pages": (total + page_size - 1) // page_size,
        }

        return results, meta

    async def first(
        self,
        *args: Any,
        ids: list[uuid.UUID] | None = None,
        field_name_for_ids: str | None = None,
        sort: str | None = None,
        select_related: str | list[str] | None = None,
        prefetch_related: str | list[str] | None = None,
        **kwargs: Any
    ) -> _ModelT | None:
        query = self._model.filter(*args, **kwargs)

        if ids and field_name_for_ids:
            query = query.filter(**{f"{field_name_for_ids}__in": ids})

        if select_related:
            if isinstance(select_related, str):
                select_related = [select_related]
            query = query.select_related(*select_related)

        if prefetch_related:
            if isinstance(prefetch_related, str):
                prefetch_related = [prefetch_related]
            query = query.prefetch_related(*prefetch_related)

        if sort:
            order_fields = [field.strip() for field in sort.split(",")]
            query = query.order_by(*order_fields)

        return await query.first()

    async def filter_existing_ids(self, ids: list[uuid.UUID]) -> list[uuid.UUID]:
        return await self._model.filter(id__in=ids).values_list("id", flat=True)

    async def bulk_create(
        self,
        objects: Iterable[_ModelT | dict[str, Any]],
        ignore_conflicts: bool = False
    ) -> list[_ModelT]:

        if isinstance(next(iter(objects)), dict):
            model_instances = [self._model(**obj) for obj in objects]  # type: ignore
        else:
            model_instances = list(objects)

        await self._model.bulk_create(
            model_instances,
            ignore_conflicts=ignore_conflicts
        )

        return model_instances

    # async def update(self, instance: _ModelT, **kwargs: Any) -> _ModelT | None:
    #     for attr, value in kwargs.items():
    #         setattr(instance, attr, value)
    #     await instance.save()
    #     return instance
    #
    # async def update_by_id(self, id: uuid.UUID, **kwargs: Any) -> _ModelT | None:
    #     instance = await self.get_by_id(id)
    #     if instance:
    #         return await self.update(instance, **kwargs)
    #     return None

    async def update(self, target: uuid.UUID | _ModelT, **kwargs: Any) -> _ModelT | None:
        if isinstance(target, uuid.UUID):
            instance = await self.get_by_id(target)
            if instance is None:
                return None
        else:
            instance = target

        for attr, value in kwargs.items():
            setattr(instance, attr, value)

        await instance.save()
        return instance

    async def delete(self, instance: _ModelT) -> None:
        await instance.delete()

    async def delete_by_id(self, id: uuid.UUID) -> bool:
        instance = await self.get_by_id(id)
        if instance:
            await instance.delete()
            return True
        return False

    async def delete_by_filter(self, *args: Any, **kwargs: Any) -> int:
        return await self._model.filter(*args, **kwargs).delete()


# schema - request + response + validation
class BaseSchema(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    @cached_property
    def _tag(self) -> str:
        return self.__class__.__name__

    def to_json(self, exclude_none: bool = True) -> Any:
        json = jsonable_encoder(self, exclude_none=exclude_none)
        logger.info(f"{self._tag}|to_json(): {json}")
        return json

    def to_dict(
        self,
        exclude_fields: Annotated[set[str] | None, Field(...)] = None,
        exclude_none: Annotated[bool, Field(...)] = None,
        exclude_unset: Annotated[bool, Field(...)] = None,
    ) -> dict[str, Any]:
        data = self.model_dump(
            exclude=exclude_fields,
            exclude_none=exclude_none,
            exclude_unset=exclude_unset
        )
        logger.info(f"{self._tag}|to_dict(): {data}")
        return data

    def safe_dump(
        self, exclude_fields: Annotated[set[str] | None, Field(...)] = None,
    ) -> dict[str, Any]:
        data = self.to_dict(
            exclude_fields=exclude_fields,
            exclude_none=True,
            exclude_unset=True
        )
        logger.info(f"{self._tag}|to_dict(): {data}")
        return data

    def log(self) -> None:
        data = self.model_dump()
        logger.info(f"{self._tag}|log(): {data}")


# service
class BaseService:

    def __init__(self) -> None:
        logger.debug(f"{self._tag}|__init__()")

    @cached_property
    def _tag(self) -> str:
        return self.__class__.__name__
