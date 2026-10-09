import uuid
from pathlib import Path

from src.service.download.paths import collection_relpath, final_path, part_path, work_dir


def test_a_collection_with_no_folder_sits_at_the_root() -> None:
    assert collection_relpath(None, "Talks", "PL1") == "Talks [PL1]"


def test_a_collection_sits_inside_its_folders_directory() -> None:
    assert collection_relpath("Video", "Talks", "PL1") == str(Path("Video") / "Talks [PL1]")


def test_work_dir_is_namespaced_by_id() -> None:
    download_id = uuid.uuid4()
    assert work_dir(Path("/downloads"), download_id) == Path("/downloads") / str(download_id)


def test_part_path_appends_the_part_suffix() -> None:
    download_id = uuid.uuid4()
    assert part_path(Path("/downloads"), download_id, "video").name == "video.part"


def test_final_path_uses_the_filename() -> None:
    download_id = uuid.uuid4()
    assert final_path(Path("/downloads"), download_id, "clip.mp4").name == "clip.mp4"


def test_final_path_strips_directory_components_from_the_filename() -> None:
    download_id = uuid.uuid4()
    path = final_path(Path("/downloads"), download_id, "../../etc/passwd")
    assert path.parent == work_dir(Path("/downloads"), download_id)
    assert path.name == "passwd"


def test_a_stored_collection_folder_wins_over_the_derived_one() -> None:
    from types import SimpleNamespace

    from src.service.download.paths import container_folder

    stored = SimpleNamespace(folder="music/Talks [PL1]", id=uuid.uuid4())
    assert container_folder(stored) == "music/Talks [PL1]"


def test_a_standalone_download_is_where_its_row_says_or_its_id_folder_from_before_categories() -> None:
    from types import SimpleNamespace

    from src.service.download.paths import placed_folder

    download_id = uuid.UUID(int=7)
    assert placed_folder(SimpleNamespace(id=download_id, folder="videos")) == "videos"
    assert placed_folder(SimpleNamespace(id=download_id, folder=None)) == str(download_id)


def test_an_empty_stored_folder_is_the_download_root_not_an_unset_one() -> None:
    from types import SimpleNamespace

    from src.service.download.paths import placed_folder

    assert placed_folder(SimpleNamespace(id=uuid.UUID(int=7), folder="")) == ""
