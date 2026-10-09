"""Download carries no save_dir, path or position: its place is ``folder``, set when its files land."""

from src.data.db.model.transfer.download import Download


def test_download_has_no_placement_columns() -> None:
    fields = set(Download._meta.fields_map.keys())
    assert "position" not in fields
    assert "save_dir" not in fields
    assert "path" not in fields
