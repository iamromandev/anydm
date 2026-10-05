"""Segment: one byte range of one file, its progress, and whether it finished."""

from src.data.db.model.transfer.segment import Segment
from src.data.type import SegmentStatus
from tortoise.fields.relational import ForeignKeyFieldInstance


def test_segment_has_the_schema_columns() -> None:
    names = set(Segment._meta.db_fields)
    assert {"start_byte", "end_byte", "downloaded_bytes", "status", "created_at", "updated_at"} <= names
    assert "file" in Segment._meta.fk_fields
    assert "deleted_at" not in names


def test_segment_dropped_the_old_columns() -> None:
    names = set(Segment._meta.fields_map)
    for gone in ("download", "part", "index", "downloaded"):
        assert gone not in names, gone


def test_segment_file_is_a_cascade_fk() -> None:
    field = Segment._meta.fields_map["file"]
    assert isinstance(field, ForeignKeyFieldInstance)
    assert field.null is False
    assert field.model_name == "model.File"
    assert field.related_name == "segments"
    assert getattr(field, "on_delete", None) == "CASCADE"


def test_segment_status_defaults_to_pending() -> None:
    assert Segment._meta.fields_map["status"].default is SegmentStatus.PENDING


def test_segment_status_values() -> None:
    assert (SegmentStatus.PENDING, SegmentStatus.DOWNLOADING, SegmentStatus.COMPLETED, SegmentStatus.FAILED) == (
        "pending",
        "downloading",
        "completed",
        "failed",
    )


def test_segment_columns() -> None:
    from tortoise import fields

    fields_map = Segment._meta.fields_map
    assert isinstance(fields_map["start_byte"], fields.BigIntField)
    assert fields_map["start_byte"].null is False
    assert isinstance(fields_map["end_byte"], fields.BigIntField)
    assert fields_map["end_byte"].null is False
    assert isinstance(fields_map["downloaded_bytes"], fields.BigIntField)
    assert fields_map["downloaded_bytes"].default == 0


def test_segment_meta_is_transfer() -> None:
    assert Segment.Meta.table == "segment"
    assert Segment.Meta.schema == "transfer"
