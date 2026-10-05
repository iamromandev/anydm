"""Every file row records what it is, whichever kind of download it came from."""

import pytest
from src.data.db.model import DownloadFile
from src.data.repo import DownloadDatabaseRepo, FileDatabaseRepo
from src.data.repo.download.mime import mime_of
from src.data.type import DownloadStatus


@pytest.mark.parametrize(
    ("name", "expected"),
    [
        ("report.pdf", "application/pdf"),
        ("movie.mp4", "video/mp4"),
        ("song.mp3", "audio/mpeg"),
        ("archive.zip", "application/zip"),
        ("notes.txt", "text/plain"),
    ],
)
def test_the_type_is_guessed_from_the_name(name: str, expected: str) -> None:
    assert mime_of(name) == expected


def test_a_name_with_no_known_type_is_none_not_a_guess() -> None:
    assert mime_of("data.unknownext") is None
    assert mime_of("Makefile") is None
    assert mime_of("") is None


def test_a_path_is_judged_by_its_file_name() -> None:
    assert mime_of("Season 1/E01.mp4") == "video/mp4"


def direct_fields(name: str) -> dict:
    return {
        "status": DownloadStatus.PENDING,
    }


@pytest.mark.asyncio
async def test_a_direct_download_records_its_files_type(sqlite: None) -> None:

    download = await DownloadDatabaseRepo().create_direct(direct_fields("report.pdf"), "report.pdf")

    (file,) = await DownloadFile.filter(download_id=download.id)
    assert file.mime_type == "application/pdf"


@pytest.mark.asyncio
async def test_each_file_of_a_torrent_records_its_own_type(sqlite: None) -> None:
    fields = direct_fields("torrent")

    download = await DownloadDatabaseRepo().create_torrent(
        fields, [(0, "Show/E01.mp4", 10, True), (1, "Show/cover.jpg", 1, True), (2, "Show/README", 1, False)]
    )

    types = {f.index: f.mime_type for f in await DownloadFile.filter(download_id=download.id)}
    assert types == {0: "video/mp4", 1: "image/jpeg", 2: None}


@pytest.mark.asyncio
async def test_replacing_a_files_list_records_the_types_too(sqlite: None) -> None:
    download = await DownloadDatabaseRepo().create_direct(direct_fields("a.bin"), "a.bin")

    await FileDatabaseRepo().replace(download.id, [(0, "a.mkv", 5, True), (1, "b.pdf", 5, True)])

    types = {f.index: f.mime_type for f in await DownloadFile.filter(download_id=download.id)}
    assert types == {0: "video/x-matroska", 1: "application/pdf"}
