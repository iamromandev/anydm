"""No Folder model: downloads are filed by derived location, and the schema
``category`` key stays only as an always-null contract placeholder."""

from src.data.db import model


def test_there_is_no_folder_model() -> None:
    assert not hasattr(model, "Folder")
