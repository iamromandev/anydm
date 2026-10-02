import uuid
from pathlib import Path

from src.service.download.paths import final_path, part_path, work_dir


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
