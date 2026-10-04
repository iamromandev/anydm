"""No checksum storage on Download: verification has no writers, so the columns go."""

from src.data.db.model.transfer.download import Download


def test_download_has_no_checksum_columns() -> None:
    fields = set(Download._meta.fields_map.keys())
    assert "checksum_algo" not in fields
    assert "checksum_expected" not in fields
    assert "checksum_ok" not in fields
