"""No Folder model: a download's place is its category's folder, recorded in ``download.folder``."""

from src.data.db import model


def test_there_is_no_folder_model() -> None:
    assert not hasattr(model, "Folder")
