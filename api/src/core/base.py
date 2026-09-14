# database - model + repo
from __future__ import annotations

import uuid
from abc import ABC, abstractmethod
from collections.abc import Iterable, Sequence
from datetime import UTC, datetime
from typing import Annotated, Any, Self, cast

from fastapi.encoders import jsonable_encoder
from loguru import logger
from pydantic import BaseModel, ConfigDict, Field
from tortoise import fields, models, queryset
from tortoise.exceptions import DoesNotExist
from tortoise.fields.db_defaults import Now
from tortoise.query_utils import Prefetch

from src.core.common import as_list

# Uniqueness rules live in ``Meta.constraints`` as ``UniqueConstraint``
# (partial uniques carry ``condition=``), the same way platform declares them;
# plain uniques use ``unique_together``. There is no custom index class here.
#
# Known gap, accepted deliberately: Tortoise 1.1.8 applies ``Meta.constraints``
# only when an *existing* model's options change. ``CreateModel`` renders
# ``unique_together`` and ``Meta.indexes`` and nothing else, so a database
# built from scratch by ``make migrate`` gets none of these rules until an
# ``AddConstraint`` reaches it. Existing databases keep the indexes they were
# built with.


class IdBase(models.Model):
    """A UUID primary key and nothing else — no timestamps.

    The bottom of the model ladder: ``IdBase`` → ``StampBase`` → ``Base`` →
    ``SoftBase``. Each rung adds one column, so a model inherits exactly the
    bookkeeping it wants rather than carrying a ``deleted_at`` it will never
    set. Everything shared by every row — the Pydantic defaults,
    ``db_fields``, ``from_query_result`` — is declared once, here.
    """

    id: uuid.UUID = fields.UUIDField(primary_key=True, default=uuid.uuid4)

    class Meta:
        abstract = True

    class PydanticMeta:
        """Defaults for ``tortoise.contrib.pydantic.pydantic_model_creator`` on subclasses."""

        exclude = ("Meta",)
        exclude_raw_fields = True
        backward_relations = False
        max_recursion = 3
        allow_cycles = False
        sort_alphabetically = False
        model_config = ConfigDict(from_attributes=True)

    @classmethod
    def db_fields(cls, excludes: list[str] | None = None) -> list[str]:
        excludes = excludes or []
        return [f for f in sorted(cls._meta.db_fields) if f not in excludes]

    @classmethod
    def from_query_result(cls: type[Self], *args: Any, **kwargs: Any) -> Self:
        """Instantiate a model instance from raw query result."""
        if args:
            msg = (
                "Positional args are not supported; pass column values as kwargs "
                "(db column names or model field names)."
            )
            raise ValueError(msg)
        reverse = cls._meta.fields_db_projection_reverse
        mapped: dict[str, Any] = {}
        for key, value in kwargs.items():
            model_field = reverse.get(key, key)
            mapped[model_field] = value
        return cls.construct(_saved_in_db=True, **mapped)


class StampBase(IdBase):
    """``IdBase`` plus ``created_at``, for rows that are written once and never edited.

    Association rows and append-only records land here: they want to know when
    they were made, and an ``updated_at`` on a row nothing updates is a column
    that lies.
    """

    created_at: datetime = fields.DatetimeField(auto_now_add=True, db_index=True)

    class Meta:
        abstract = True


class Base(StampBase):
    """``StampBase`` plus ``updated_at``. Soft delete is opt-in, via ``SoftBase``."""

    updated_at: datetime = fields.DatetimeField(
        auto_now=True, db_index=True, db_default=Now()
    )

    class Meta:
        abstract = True


class SoftBase(Base):
    """``Base`` plus ``deleted_at``, the column every ``WHERE deleted_at IS NULL`` partial unique index keys off."""

    deleted_at: datetime | None = fields.DatetimeField(null=True, db_index=True)

    class Meta:
        abstract = True

    async def soft_delete(self) -> None:
        self.deleted_at = datetime.now(UTC)
        await self.save()

    @classmethod
    def get_active(cls: type[Self]) -> queryset.QuerySet[Self]:
        return cls.filter(deleted_at__isnull=True)


class CrudRepo[M: models.Model](ABC):
    """Common repository contract implemented by ``BaseRepo``."""

    @abstractmethod
    async def exists(self, *args: Any, **kwargs: Any) -> bool: ...

    @abstractmethod
    async def create(self, **kwargs: Any) -> M: ...

    @abstractmethod
    async def get_or_create(self, **kwargs: Any) -> tuple[M, bool]: ...

    @abstractmethod
    async def get_or_none(self, **kwargs: Any) -> M | None: ...

    @abstractmethod
    async def get_by_id(self, id: uuid.UUID, **kwargs: Any) -> M | None: ...

    @abstractmethod
    async def get_one(self, *args: Any, **kwargs: Any) -> M | None: ...

    @abstractmethod
    async def get_many(self, *args: Any, **kwargs: Any) -> list[M]: ...

    @abstractmethod
    async def get_paginated(self, *args: Any, **kwargs: Any) -> tuple[list[M], dict[str, int]]: ...

    @abstractmethod
    async def bulk_create(
        self,
        objects: Iterable[M | dict[str, Any]],
        ignore_conflicts: bool = False,
    ) -> list[M]: ...

    @abstractmethod
    async def update(self, target: uuid.UUID | M, **kwargs: Any) -> M | None: ...

    @abstractmethod
    async def delete(self, target: uuid.UUID | M) -> bool: ...


# repo - operation on the database
class BaseRepo[M: models.Model]:
    _model: type[M]

    def __init__(self, model: type[M]) -> None:
        self._model = model

    @property
    def _tag(self) -> str:
        return self.__class__.__name__

    def _reject_unknown_fields(self, names: Iterable[str], *, operation: str) -> None:
        """Fail on field names the model has no column for.

        Tortoise accepts unknown kwargs without complaint: ``Model(**kwargs)``
        keeps them as plain instance attributes and ``save()`` writes only real
        columns, so a renamed or mistyped field is a silent no-op rather than an
        error. That is how a sealed provider secret was written to a connection
        for weeks without ever reaching the row. Every write path checks, so the
        typo surfaces where it is made.
        """
        meta = self._model._meta
        # ``db_fields`` carries the columns, including the ``tenant_id`` form of
        # a foreign key; ``fk_fields``/``o2o_fields`` carry the ``tenant`` form
        # of the same assignment. Reverse relations are deliberately absent:
        # they are not assignable.
        known = meta.db_fields | meta.fk_fields | meta.o2o_fields
        unknown = sorted(name for name in names if name not in known)
        if unknown:
            raise ValueError(
                f"{self._model.__name__}.{operation} got field(s) the model does not "
                f"define: {', '.join(unknown)}"
            )

    async def count(self, *args: Any, **kwargs: Any) -> int:
        return await self._model.filter(*args, **kwargs).count()

    async def first_by_raw(self, sql: str) -> M | None:
        rows = await self._model.raw(sql)  # type hint to satisfy checker
        if not rows:
            return None
        return rows[0]

    async def exists(self, *args: Any, **kwargs: Any) -> bool:
        return await self._model.filter(*args, **kwargs).exists()

    async def get_or_create(self, **kwargs: Any) -> tuple[M, bool]:
        # Only ``defaults`` names fields. The rest are lookups, free to carry
        # ``__`` operators (``deleted_at__isnull``, ``provider__slug``) that no
        # field-name check would recognise.
        defaults = kwargs.get("defaults")
        if defaults:
            self._reject_unknown_fields(defaults, operation="get_or_create defaults")
        return await self._model.get_or_create(**kwargs)

    async def create(self, **kwargs: Any) -> M:
        self._reject_unknown_fields(kwargs, operation="create")
        return await self._model.create(**kwargs)

    async def get_or_none(self, **kwargs: Any) -> M | None:
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
        **kwargs: Any,
    ) -> M | None:
        # The named options shape the query; ``kwargs`` narrows the row itself,
        # so ``get_by_id(id, deleted_at__isnull=True)`` is one round trip rather
        # than a fetch followed by a tombstone check.
        query: queryset.QuerySet[M] = self._model.filter(id=id, **kwargs)

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

    async def get_ids(
        self,
        *args: Any,
        ids: list[uuid.UUID] | None = None,
        field_name_for_ids: str | None = None,
        order_by: str | None = None,
        limit: int | None = None,
        distinct: bool = False,
        **kwargs: Any,
    ) -> list[uuid.UUID]:
        query: queryset.QuerySet[M] = self._model.filter(*args, **kwargs)

        if ids and field_name_for_ids:
            query = query.filter(**{f"{field_name_for_ids}__in": ids})

        if order_by:
            order_fields = [field.strip() for field in order_by.split(",")]
            query = query.order_by(*order_fields)

        if limit:
            query = query.limit(limit)

        if distinct:
            query = query.distinct()

        # ``flat=True`` yields the scalars themselves, and Tortoise refuses the
        # combination for anything but a single field. Its stubs key the awaited
        # type off the query alone, so they read every values_list as tuples.
        return cast(list[uuid.UUID], await query.values_list("id", flat=True))

    async def get_one(
        self,
        *args: Any,
        ids: list[uuid.UUID] | None = None,
        field_name_for_ids: str | None = None,
        select_related: str | list[str] | None = None,
        prefetch_related: str | Prefetch | Iterable[str | Prefetch] | None = None,
        annotations: dict[str, Any] | None = None,
        order_by: str | None = None,
        **kwargs: Any
    ) -> M | None:
        query = self._model.filter(*args, **kwargs)

        if ids and field_name_for_ids:
            query = query.filter(**{f"{field_name_for_ids}__in": ids})

        if select_related:
            if isinstance(select_related, str):
                select_related = [select_related]
            query = query.select_related(*select_related)

        if prefetch_related:
            query = query.prefetch_related(*as_list(prefetch_related))

        # Apply annotations (like Count)
        if annotations:
            query = query.annotate(**annotations)

        if order_by:
            order_fields = [field.strip() for field in order_by.split(",")]
            query = query.order_by(*order_fields)

        return await query.first()

    async def get_many(
        self,
        *args: Any,
        ids: list[uuid.UUID] | None = None,
        field_name_for_ids: str | None = None,
        select_related: str | Sequence[str] | None = None,
        prefetch_related: str | Prefetch | Iterable[str | Prefetch] | None = None,
        annotations: dict[str, Any] | None = None,
        order_by: str | None = None,
        limit: int | None = None,
        distinct: bool = False,
        **kwargs: Any
    ) -> list[M]:
        query: queryset.QuerySet[M] = self._model.filter(*args, **kwargs)

        if ids and field_name_for_ids:
            query = query.filter(**{f"{field_name_for_ids}__in": ids})

        if select_related:
            if isinstance(select_related, str):
                select_related = [select_related]
            query = query.select_related(*select_related)

        if prefetch_related:
            query = query.prefetch_related(*as_list(prefetch_related))

        if annotations:
            query = query.annotate(**annotations)

        if order_by:
            order_fields = [field.strip() for field in order_by.split(",")]
            query = query.order_by(*order_fields)

        if limit:
            query = query.limit(limit)

        if distinct:
            query = query.distinct()

        return await query

    async def get_paginated(
        self,
        *args: Any,
        ids: list[uuid.UUID] | None = None,
        field_name_for_ids: str | None = None,
        select_related: str | list[str] | None = None,
        prefetch_related: str | Prefetch | Iterable[str | Prefetch] | None = None,
        annotations: dict[str, Any] | None = None,
        distinct: bool = False,
        order_by: str | None = None,
        page: int = 1,
        page_size: int = 10,
        limit: int | None = None,
        **kwargs: Any
    ) -> tuple[list[M], dict[str, int]]:
        query: queryset.QuerySet[M] = self._model.filter(*args, **kwargs)

        if ids and field_name_for_ids:
            query = query.filter(**{f"{field_name_for_ids}__in": ids})

        if select_related:
            if isinstance(select_related, str):
                select_related = [select_related]
            query = query.select_related(*select_related)

        if prefetch_related:
            query = query.prefetch_related(*as_list(prefetch_related))

        if annotations:
            query = query.annotate(**annotations)

        if distinct:
            query = query.distinct()

        if order_by:
            order_fields = [field.strip() for field in order_by.split(",")]
            query = query.order_by(*order_fields)

        raw_total: int = await query.count()

        effective_offset = (page - 1) * page_size
        effective_page_size = page_size

        if limit is not None:
            # total respecting the limit
            total: int = min(raw_total, limit)

            if effective_offset >= limit:
                # Page is beyond the limit
                results: list[M] = []
                effective_total_pages = (limit + page_size - 1) // page_size
            else:
                # Adjust page_size if this page exceeds limit
                remaining = limit - effective_offset
                effective_page_size = min(page_size, remaining)
                results: list[M] = await query.offset(effective_offset).limit(effective_page_size)
                effective_total_pages = (limit + page_size - 1) // page_size
        else:
            total = raw_total
            results: list[M] = await query.offset(effective_offset).limit(effective_page_size)
            effective_total_pages = (total + page_size - 1) // page_size

        meta: dict[str, int] = {
            "page": page,
            "page_size": page_size,
            "total": total,
            "total_pages": effective_total_pages,
        }

        return results, meta

    async def filter_existing_ids(self, ids: list[uuid.UUID]) -> list[uuid.UUID]:
        # ``flat=True`` again — see ``get_ids`` for why the stub reads it as tuples.
        return cast(list[uuid.UUID], await self._model.filter(id__in=ids).values_list("id", flat=True))

    async def bulk_create(
        self,
        objects: Iterable[M | dict[str, Any]],
        ignore_conflicts: bool = False,
    ) -> list[M]:
        objects = list(objects)
        if not objects:
            return []

        # Narrowed per element rather than from the first one: a list holding both
        # dicts and instances is well within what the signature accepts, and reading
        # the whole list off ``objects[0]`` broke it whichever branch it chose.
        model_instances: list[M] = [self._model(**obj) if isinstance(obj, dict) else obj for obj in objects]

        await self._model.bulk_create(
            model_instances,
            ignore_conflicts=ignore_conflicts,
        )

        return model_instances

    async def update(self, target: uuid.UUID | M, **kwargs: Any) -> M | None:
        if isinstance(target, uuid.UUID):
            instance = await self.get_by_id(target)
            if instance is None:
                return None
        else:
            instance = target

        self._reject_unknown_fields(kwargs, operation="update")
        for attr, value in kwargs.items():
            setattr(instance, attr, value)

        await instance.save()
        return instance

    async def delete(self, target: uuid.UUID | M) -> bool:
        if isinstance(target, uuid.UUID):
            instance = await self.get_by_id(target)
        else:
            instance = target

        if not instance:
            return False

        await instance.delete()
        return True

    async def delete_by_filter(self, *args: Any, **kwargs: Any) -> int:
        return await self._model.filter(*args, **kwargs).delete()


# schema - request + response + validation
class BaseSchema(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    @property
    def _tag(self) -> str:
        return self.__class__.__name__

    def to_json(self, exclude_none: bool = True) -> Any:
        json = jsonable_encoder(self, exclude_none=exclude_none)
        logger.info(f"{self._tag}|to_json(): {json}")
        return json

    def to_dict(
        self,
        exclude_fields: Annotated[set[str] | None, Field(...)] = None,
        exclude_none: bool = False,
        exclude_unset: bool = False,
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

    @property
    def _tag(self) -> str:
        return self.__class__.__name__
