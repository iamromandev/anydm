"""Attempt: one try via one Mirror, with its running/finished state."""

from src.data.db.model.transfer.attempt import Attempt
from src.data.type import AttemptStatus
from tortoise.fields.relational import ForeignKeyFieldInstance


def test_attempt_has_the_schema_columns() -> None:
    names = set(Attempt._meta.db_fields)
    assert {"status", "started_at", "completed_at", "downloaded_bytes", "created_at", "updated_at"} <= names
    assert "mirror" in Attempt._meta.fk_fields
    assert "deleted_at" not in names


def test_attempt_mirror_is_a_cascade_fk() -> None:
    field = Attempt._meta.fields_map["mirror"]
    assert isinstance(field, ForeignKeyFieldInstance)
    assert field.null is False
    assert field.model_name == "model.Mirror"
    assert field.related_name == "attempts"
    assert getattr(field, "on_delete", None) == "CASCADE"


def test_attempt_dropped_the_download_fk_and_error() -> None:
    assert "download" not in Attempt._meta.fields_map
    assert "error" not in Attempt._meta.fields_map


def test_attempt_status_defaults_to_running() -> None:
    assert Attempt._meta.fields_map["status"].default is AttemptStatus.RUNNING


def test_attempt_status_values() -> None:
    assert (AttemptStatus.RUNNING, AttemptStatus.COMPLETED, AttemptStatus.FAILED, AttemptStatus.CANCELLED) == (
        "running",
        "completed",
        "failed",
        "cancelled",
    )


def test_attempt_columns_and_defaults() -> None:
    from tortoise import fields

    fields_map = Attempt._meta.fields_map
    assert isinstance(fields_map["started_at"], fields.DatetimeField)
    assert isinstance(fields_map["completed_at"], fields.DatetimeField)
    assert fields_map["completed_at"].null is True
    assert isinstance(fields_map["downloaded_bytes"], fields.BigIntField)
    assert fields_map["downloaded_bytes"].default == 0


def test_attempt_meta_is_transfer() -> None:
    assert Attempt.Meta.table == "attempt"
    assert Attempt.Meta.schema == "transfer"
