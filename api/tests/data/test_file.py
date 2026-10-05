"""File: a file belonging to one download: its name and path, size, index, progress, selection and type."""

from src.data.db.model.transfer.file import File
from tortoise.fields import CharField
from tortoise.fields.relational import ForeignKeyFieldInstance


def test_file_has_the_schema_columns() -> None:
    names = set(File._meta.db_fields)
    assert {
        "filename",
        "path",
        "size",
        "index",
        "downloaded_bytes",
        "selected",
        "mime_type",
        "created_at",
        "updated_at",
    } <= names
    assert "download" in File._meta.fk_fields
    assert "deleted_at" not in names


def test_file_download_is_a_cascade_fk() -> None:
    field = File._meta.fields_map["download"]
    assert isinstance(field, ForeignKeyFieldInstance)
    assert field.null is False
    assert field.model_name == "model.Download"
    assert field.related_name == "files"
    assert getattr(field, "on_delete", None) == "CASCADE"


def test_file_filename_and_path_and_size() -> None:
    fields_map = File._meta.fields_map
    assert isinstance(fields_map["filename"], CharField)
    assert fields_map["filename"].max_length == 512
    assert fields_map["filename"].null is False
    from tortoise import fields

    assert isinstance(fields_map["path"], fields.TextField)
    assert fields_map["path"].null is False
    assert isinstance(fields_map["size"], fields.BigIntField)
    assert fields_map["size"].null is True


def test_file_meta_is_file_table_in_transfer() -> None:
    assert File.Meta.table == "file"
    assert File.Meta.schema == "transfer"


def test_file_index_progress_selection_and_type() -> None:
    from tortoise import fields

    fields_map = File._meta.fields_map
    assert isinstance(fields_map["index"], fields.IntField)
    assert fields_map["index"].null is False
    assert isinstance(fields_map["downloaded_bytes"], fields.BigIntField)
    assert fields_map["downloaded_bytes"].default == 0
    assert isinstance(fields_map["selected"], fields.BooleanField)
    assert fields_map["selected"].default is True
    assert isinstance(fields_map["mime_type"], CharField)
    assert fields_map["mime_type"].max_length == 128
    assert fields_map["mime_type"].null is True


def test_file_index_is_unique_per_download_and_orders_rows() -> None:
    # The torrent's own file index: rqbit's selection and per-file progress key off it.
    assert ("download", "index") in File.Meta.unique_together
    assert File.Meta.ordering == ["index"]
