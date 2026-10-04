"""One download, many mirrors: each tried in position order until one works."""

from src.data.db.model.transfer.mirror import Mirror


def test_mirror_points_at_its_download_and_address() -> None:
    fields = Mirror._meta.fields_map
    assert fields["download"].null is False
    assert fields["url"].null is False
    assert getattr(fields["download"], "on_delete", None) == "CASCADE"
    assert getattr(fields["url"], "on_delete", None) == "CASCADE"


def test_mirrors_are_ordered_per_download() -> None:
    assert ("download", "position") in Mirror._meta.unique_together
