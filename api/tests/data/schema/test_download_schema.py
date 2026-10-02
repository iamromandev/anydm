import uuid

from src.data.schema.download import (
    CollectionCountsSchema,
    CollectionSchema,
    DownloadFileSchema,
    DownloadSchema,
    LiveSchema,
    PlaybackSchema,
)
from src.data.type import CollectionKind, DownloadStatus, MediaKind, Platform, Preset


def test_a_download_is_tagged_and_nests_by_concern() -> None:
    schema = DownloadSchema(
        id=uuid.uuid4(),
        source_url="https://e.com/a.mp4",
        platform=Platform.DIRECT,
        media_kind=MediaKind.FILE,
        status=DownloadStatus.DOWNLOADING,
        live=LiveSchema(speed_bps=10),
        files=[DownloadFileSchema(index=0, path="a.mp4", playback=PlaybackSchema(position_seconds=3))],
    )
    data = schema.to_json()
    assert data["type"] == "download"
    assert data["live"]["speed_bps"] == 10
    assert data["files"][0]["playback"]["position_seconds"] == 3
    assert "kind" not in data
    assert "parent_id" not in data
    assert "speed_bps" not in data


def test_a_collection_is_tagged_with_its_counts() -> None:
    schema = CollectionSchema(
        id=uuid.uuid4(),
        kind=CollectionKind.PLAYLIST,
        source_url="u",
        extractor="YoutubeTab",
        external_id="PL",
        folder="Talks",
        preset=Preset.BEST,
        status=DownloadStatus.DOWNLOADING,
        counts=CollectionCountsSchema(total=3, active=2, downloading=1),
    )
    data = schema.to_json()
    assert data["type"] == "collection"
    assert data["counts"]["active"] == 2
