import uuid
from pathlib import Path
from types import SimpleNamespace

from src.data.type import DownloadStatus, MediaKind, Platform, Preset
from src.service.download.paths import collection_relpath, container_folder, final_path, folder_of, part_path, work_dir

from tests.service.download.described_rows import a_site_row
from tests.service.download.memory import download_row


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


def _category(folder: str) -> SimpleNamespace:
    return SimpleNamespace(id=uuid.uuid4(), name="Music", folder=folder)


def test_a_collection_is_under_its_categorys_folder_named_from_its_title_and_id() -> None:
    playlist = a_site_row(
        "https://www.youtube.com/playlist?list=PL1",
        provider="Youtube",
        title="Talks",
        kind=MediaKind.PLAYLIST,
        preset=Preset.BEST,
        category=_category("music"),
    )
    assert container_folder(playlist) == "music/Talks [PL1]"
    assert folder_of(playlist) == "music/Talks [PL1]"
    root = a_site_row(
        "https://www.youtube.com/playlist?list=PL1",
        provider="Youtube",
        title="Talks",
        kind=MediaKind.PLAYLIST,
        preset=Preset.BEST,
        category=_category(""),
    )
    assert folder_of(root) == "Talks [PL1]"


def test_a_standalone_download_has_a_place_only_once_it_is_finished_and_it_is_its_categorys_folder() -> None:
    for status, expected in ((DownloadStatus.DOWNLOADING, None), (DownloadStatus.COMPLETED, "videos")):
        assert folder_of(download_row(status=status, category=_category("videos"))) == expected
    # An empty folder is the download root, not an unset one.
    assert folder_of(download_row(status=DownloadStatus.COMPLETED, category=_category(""))) == ""


def test_a_torrent_has_a_folder_of_its_own_under_its_category_from_the_moment_it_is_added() -> None:
    row = download_row(
        status=DownloadStatus.PENDING,
        platform=Platform.TORRENT,
        url="magnet:?xt=urn:btih:" + "a" * 40,
        ref_id="a" * 40,
        title="Release",
        category=_category("movies"),
    )
    assert folder_of(row) == "movies/Release [aaaaaaaa]"
    assert folder_of(download_row(platform=Platform.TORRENT, ref_id="b" * 40, title="Other", category=_category(""))) == (
        "Other [bbbbbbbb]"
    )


def test_a_collections_video_is_in_its_collections_folder_and_nowhere_without_it() -> None:
    playlist = a_site_row(
        "https://www.youtube.com/playlist?list=PL1",
        provider="Youtube",
        title="Talks",
        kind=MediaKind.PLAYLIST,
        preset=Preset.BEST,
        category=_category("music"),
        id=uuid.uuid4(),
    )
    video = download_row(parent_id=playlist.id, status=DownloadStatus.COMPLETED, category=_category("videos"))
    assert folder_of(video, playlist) == "music/Talks [PL1]"
    assert folder_of(video) is None
