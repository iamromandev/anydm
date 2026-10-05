"""Mirror: one source for one download, ordered by priority, as available/active/failed/exhausted/disabled."""

from src.data.db.model.transfer.mirror import Mirror
from src.data.type import MirrorStatus
from tortoise.fields.relational import ForeignKeyFieldInstance


def test_mirror_points_at_its_download_and_source() -> None:
    fields = Mirror._meta.fields_map
    for name, target, related in (("download", "model.Download", "mirrors"), ("source", "model.Source", "mirrors")):
        field = fields[name]
        assert isinstance(field, ForeignKeyFieldInstance)
        assert field.null is False, name
        assert field.model_name == target, name
        assert field.related_name == related, name
        assert getattr(field, "on_delete", None) == "CASCADE", name


def test_mirror_dropped_the_old_columns() -> None:
    fields = set(Mirror._meta.fields_map)
    assert "url" not in fields
    assert "position" not in fields
    assert "last_error" not in fields


def test_mirror_status_defaults_to_available() -> None:
    assert Mirror._meta.fields_map["status"].default is MirrorStatus.AVAILABLE


def test_mirror_status_values() -> None:
    assert (MirrorStatus.AVAILABLE, MirrorStatus.ACTIVE, MirrorStatus.FAILED, MirrorStatus.EXHAUSTED, MirrorStatus.DISABLED) == (
        "available",
        "active",
        "failed",
        "exhausted",
        "disabled",
    )


def test_mirror_download_source_is_unique() -> None:
    assert any(frozenset(cols) == frozenset({"download", "source"}) for cols in Mirror.Meta.unique_together)
