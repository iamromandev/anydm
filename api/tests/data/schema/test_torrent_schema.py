import pytest
from pydantic import ValidationError
from src.data.schema.torrent import FileSchema, TorrentDownloadRequest, TorrentResolveRequest, TorrentResolveResponse


def test_resolve_request_rejects_an_empty_torrent() -> None:
    with pytest.raises(ValidationError):
        TorrentResolveRequest(torrent="")


def test_resolve_response_carries_the_file_list() -> None:
    response = TorrentResolveResponse(
        info_hash="abc",
        title="Some Release",
        total_bytes=1000,
        files=[
            FileSchema(index=0, path="video.mkv", size=900, selected=True),
            FileSchema(index=1, path="readme.txt", size=100, selected=False),
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
