import uuid

import pytest
from pydantic import ValidationError
from src.data.schema.download import (
    FileSchema,
    TaskSchema,
    TorrentDownloadRequest,
    TorrentResolveRequest,
    TorrentResolveResponse,
)
from src.data.type import Kind, Platform, Preset, TaskStatus


def test_resolve_request_rejects_an_empty_torrent() -> None:
    with pytest.raises(ValidationError):
        TorrentResolveRequest(torrent="")


def test_resolve_response_carries_the_file_list() -> None:
    response = TorrentResolveResponse(
        info_hash="abc",
        title="Some Release",
        total_bytes=1000,
        files=[
            FileSchema(index=0, path="video.mkv", size_bytes=900, selected=True),
            FileSchema(index=1, path="readme.txt", size_bytes=100, selected=False),
        ],
    )
    assert [f.index for f in response.files] == [0, 1]
    assert response.files[0].downloaded_bytes == 0


def test_download_request_defaults_to_every_file() -> None:
    """An empty selection means the whole torrent, which is what rqbit does."""
    assert TorrentDownloadRequest(torrent="magnet:?xt=urn:btih:abc").files == []


def test_download_request_rejects_a_negative_index() -> None:
    with pytest.raises(ValidationError):
        TorrentDownloadRequest(torrent="magnet:?xt=urn:btih:abc", files=[-1])


def test_task_schema_exposes_the_torrent_fields() -> None:
    schema = TaskSchema(
        id=uuid.uuid4(),
        source_url="magnet:?xt=urn:btih:abc",
        platform=Platform.TORRENT,
        preset=Preset.BEST,
        kind=Kind.TORRENT,
        status=TaskStatus.SEEDING,
        info_hash="abc",
        uploaded_bytes=512,
        peers_connected=7,
        files=[FileSchema(index=0, path="video.mkv", size_bytes=900, selected=True)],
    )
    assert schema.info_hash == "abc"
    assert schema.uploaded_bytes == 512
    assert schema.peers_connected == 7
    assert schema.files is not None
    assert schema.files[0].path == "video.mkv"


def test_task_schema_omits_files_for_a_non_torrent() -> None:
    """``None`` rather than ``[]``: a missing key means "not a torrent"."""
    schema = TaskSchema(
        id=uuid.uuid4(),
        source_url="https://example.test/a.bin",
        platform=Platform.DIRECT,
        preset=Preset.BEST,
        kind=Kind.FILE,
        status=TaskStatus.PENDING,
    )
    assert schema.files is None
    assert schema.info_hash is None
    assert schema.uploaded_bytes == 0
    assert schema.peers_connected == 0
