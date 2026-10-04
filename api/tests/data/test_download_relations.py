"""Download's relations to the catalog: its provider and its primary address, when known."""

from src.data.db.model.transfer.download import Download


def test_download_points_at_its_provider() -> None:
    fields = Download._meta.fields_map
    assert "provider" in fields
    assert fields["provider"].null is True
    assert getattr(fields["provider"], "on_delete", None) == "SET NULL"


def test_download_points_at_its_primary_address() -> None:
    fields = Download._meta.fields_map
    assert "url" in fields
    assert fields["url"].null is True
    assert getattr(fields["url"], "on_delete", None) == "SET NULL"
