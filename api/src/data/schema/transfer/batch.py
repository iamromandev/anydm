import uuid
from typing import Annotated

from pydantic import Field, model_validator
from tortoise.fields.base import StrEnum

from src.core.base import BaseSchema
from src.data.type import Preset


class BatchLinks(BaseSchema):
    """What a batch is made of: a list of links, or one pattern that names them."""

    lines: Annotated[list[str] | None, Field(default=None, description="Links to add, one per entry")]
    pattern: Annotated[
        str | None,
        Field(default=None, description="A link with ranges, such as img[001-120].png; at most 1,000 links"),
    ]

    @model_validator(mode="after")
    def _one_source(self) -> BatchLinks:
        if (self.lines is None) == (self.pattern is None):
            raise ValueError("Send either lines or a pattern, not both and not neither")
        return self


class BatchRequest(BatchLinks):
    preset: Annotated[Preset, Field(default=Preset.BEST, description="Quality preset for links on a site")]
    allow_duplicate: Annotated[
        bool,
        Field(default=False, description="Add a second copy of a link the list already holds"),
    ]


class BatchResult(StrEnum):
    ADDED = "added"
    DUPLICATE = "duplicate"
    ERROR = "error"


class BatchItemSchema(BaseSchema):
    url: str
    result: BatchResult
    #: The new download, or for a duplicate the one that already has it.
    download_id: uuid.UUID | None = None
    message: str | None = None


class BatchPreviewSchema(BaseSchema):
    count: int
    urls: list[str]
