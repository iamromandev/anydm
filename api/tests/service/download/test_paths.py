import uuid
from pathlib import Path

from src.service.download.paths import final_path, part_path, task_dir


def test_task_dir_is_namespaced_by_id() -> None:
    task_id = uuid.uuid4()
    assert task_dir(Path("/downloads"), task_id) == Path("/downloads") / str(task_id)


def test_part_path_appends_the_part_suffix() -> None:
    task_id = uuid.uuid4()
    assert part_path(Path("/downloads"), task_id, "video").name == "video.part"


def test_final_path_uses_the_filename() -> None:
    task_id = uuid.uuid4()
    assert final_path(Path("/downloads"), task_id, "clip.mp4").name == "clip.mp4"


def test_final_path_strips_directory_components_from_the_filename() -> None:
    task_id = uuid.uuid4()
    path = final_path(Path("/downloads"), task_id, "../../etc/passwd")
    assert path.parent == task_dir(Path("/downloads"), task_id)
    assert path.name == "passwd"
