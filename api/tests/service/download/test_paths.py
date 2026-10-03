import uuid
from pathlib import Path
from types import SimpleNamespace

import pytest
from src.service.download.paths import collection_path, collection_relpath, final_path, part_path, work_dir


def test_a_collection_with_no_folder_sits_at_the_root() -> None:
    assert collection_relpath(None, "Talks", "PL1") == "Talks [PL1]"


def test_a_collection_sits_inside_its_folders_directory() -> None:
    assert collection_relpath("Video", "Talks", "PL1") == str(Path("Video") / "Talks [PL1]")


@pytest.mark.asyncio
async def test_collection_path_reads_the_folder_of_a_filed_collection() -> None:
    async def folder() -> SimpleNamespace:
        return SimpleNamespace(save_dir="Video")

    filed = SimpleNamespace(folder_id=uuid.uuid4(), folder=folder(), title="Talks", ref_id="PL1")

    assert await collection_path(filed) == str(Path("Video") / "Talks [PL1]")


@pytest.mark.asyncio
async def test_collection_path_needs_no_folder_when_there_is_none() -> None:
    unfiled = SimpleNamespace(folder_id=None, title="Talks", ref_id="PL1")

    assert await collection_path(unfiled) == "Talks [PL1]"


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
