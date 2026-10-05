"""Download: its folder bucket, status, sizes, priority, speed cap, retry state, soft delete, and timestamps — nothing else."""

from src.data.db.model.transfer.download import Download
from src.data.type import DownloadStatus, Folder


def test_download_has_the_diagram_columns() -> None:
    names = set(Download._meta.db_fields)
    assert {
        "folder",
        "status",
        "total_size",
        "downloaded_size",
        "uploaded_size",
        "priority",
        "speed_limit",
        "created_at",
        "started_at",
        "completed_at",
        "updated_at",
        "deleted_at",
        "error",
        "error_code",
        "attempts",
        "next_attempt_at",
    } <= names
    assert not Download._meta.fk_fields


def test_download_dropped_the_old_columns() -> None:
    names = set(Download._meta.fields_map)
    # Title, platform and media kind come from what the download was added from, not from columns here.
    for gone in (
        "parent",
        "queue",
        "provider",
        "url",
        "title",
        "platform",
        "media_kind",
        "filename",
        "destination",
        "total_bytes",
        "downloaded_bytes",
    ):
        assert gone not in names, gone


def test_download_folder_and_status_defaults() -> None:
    fields_map = Download._meta.fields_map
    assert fields_map["folder"].default is Folder.DOWNLOADS
    assert fields_map["status"].default is DownloadStatus.PENDING


def test_download_required_vs_nullable() -> None:
    fields_map = Download._meta.fields_map
    for name in ("folder", "status", "downloaded_size", "uploaded_size", "priority", "attempts"):
        assert fields_map[name].null is False, name
    for name in (
        "total_size",
        "speed_limit",
        "started_at",
        "completed_at",
        "deleted_at",
        "error",
        "error_code",
        "next_attempt_at",
    ):
        assert fields_map[name].null is True, name


def test_download_column_types_and_lengths() -> None:
    fields_map = Download._meta.fields_map
    assert getattr(fields_map["status"], "enum_type", None) is DownloadStatus
    assert getattr(fields_map["folder"], "enum_type", None) is Folder
    from tortoise import fields

    assert isinstance(fields_map["total_size"], fields.BigIntField)
    assert isinstance(fields_map["downloaded_size"], fields.BigIntField)
    assert isinstance(fields_map["uploaded_size"], fields.BigIntField)
    assert isinstance(fields_map["priority"], fields.IntField)
    assert isinstance(fields_map["speed_limit"], fields.BigIntField)
    assert isinstance(fields_map["started_at"], fields.DatetimeField)
    assert isinstance(fields_map["completed_at"], fields.DatetimeField)
    assert isinstance(fields_map["error"], fields.TextField)
    assert isinstance(fields_map["error_code"], fields.CharField)
    assert fields_map["error_code"].max_length == 64
    assert isinstance(fields_map["attempts"], fields.IntField)
    assert isinstance(fields_map["next_attempt_at"], fields.DatetimeField)


def test_download_defaults() -> None:
    fields_map = Download._meta.fields_map
    assert fields_map["downloaded_size"].default == 0
    assert fields_map["priority"].default == 0
    assert fields_map["attempts"].default == 0


def test_download_soft_delete_and_status_are_indexed() -> None:
    fields_map = Download._meta.fields_map
    assert fields_map["deleted_at"].index is True
    assert fields_map["status"].index is True
    assert any(index.name == "idx_download_status_created" for index in Download.Meta.indexes)

