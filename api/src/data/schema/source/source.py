import uuid

from pydantic import field_validator, model_validator

from src.core.base import BaseSchema
from src.lib.sources.registry import KINDS, valid_name


class SourceSchema(BaseSchema):
    #: Always set: views come from stored rows.
    id: uuid.UUID
    name: str
    kind: str
    enabled: bool
    base_url: str
    #: Null when no key is stored; a short key is never shown whole.
    api_key_masked: str | None
    #: False exactly for registry rows, which can be switched and re-addressed but never deleted.
    deletable: bool
    #: The registry's address for a built-in kind; null for torznab, which has no default.
    default_url: str | None


class SourceListSchema(BaseSchema):
    sources: list[SourceSchema]


class SourceCreate(BaseSchema):
    name: str
    kind: str
    base_url: str
    api_key: str | None = None
    enabled: bool = True

    @field_validator("name")
    @classmethod
    def _known_name(cls, name: str) -> str:
        if not valid_name(name):
            raise ValueError("A source name is lowercase letters, digits and dashes, up to 64 characters")
        return name

    @field_validator("kind")
    @classmethod
    def _known_kind(cls, kind: str) -> str:
        if kind not in KINDS:
            raise ValueError(f"Unknown source kind: {kind}")
        return kind

    @model_validator(mode="after")
    def _key_needs_torznab(self) -> SourceCreate:
        if self.api_key is not None and self.kind != "torznab":
            raise ValueError("An API key only means anything for a Torznab indexer")
        return self


class SourcePatch(BaseSchema):
    enabled: bool | None = None
    base_url: str | None = None
    #: Omitted keeps the stored key, "" clears it, anything else sets it.
    api_key: str | None = None

    @model_validator(mode="after")
    def _something_to_change(self) -> SourcePatch:
        if self.enabled is None and self.base_url is None and self.api_key is None:
            raise ValueError("Give enabled, base_url, api_key, or several")
        return self


class SourceTestRequest(BaseSchema):
    #: Try an unsaved address (and key) before committing to it.
    base_url: str | None = None
    api_key: str | None = None


class SourceProbeRequest(BaseSchema):
    kind: str
    base_url: str
    api_key: str | None = None

    @field_validator("kind")
    @classmethod
    def _known_kind(cls, kind: str) -> str:
        if kind not in KINDS:
            raise ValueError(f"Unknown source kind: {kind}")
        return kind

    @model_validator(mode="after")
    def _key_needs_torznab(self) -> SourceProbeRequest:
        if self.api_key is not None and self.kind != "torznab":
            raise ValueError("An API key only means anything for a Torznab indexer")
        return self


class SourceTestSchema(BaseSchema):
    ok: bool
    #: How many results it found; absent when the test failed.
    count: int | None = None
    took_ms: int
    message: str
