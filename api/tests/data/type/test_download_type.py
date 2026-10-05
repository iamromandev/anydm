import pytest
from src.data.type import (
    ACTIVE_STATUSES,
    DOWNLOAD_GROUPS,
    OTHER_FOLDER,
    ChecksumAlgo,
    CollectionKind,
    DownloadStatus,
    MediaKind,
    Platform,
    Preset,
    SegmentPart,
)


def test_platform_is_how_bytes_arrive() -> None:
    assert [p.value for p in Platform] == ["site", "direct", "torrent"]


def test_media_kind_is_what_they_are() -> None:
    assert [k.value for k in MediaKind] == ["video", "audio", "file", "playlist", "channel"]


def test_segment_parts_are_the_workers_part_names() -> None:
    assert [p.value for p in SegmentPart] == ["file", "video", "audio"]


def test_status_members() -> None:
    # SEEDING and MUXING are back (#460); QUEUED stays for named queues (#234).
    assert [s.value for s in DownloadStatus] == [
        "pending",
        "queued",
        "downloading",
        "muxing",
        "paused",
        "seeding",
        "completed",
        "failed",
        "cancelled",
    ]


def test_active_statuses_are_a_worker_mid_flight() -> None:
    assert {DownloadStatus.DOWNLOADING, DownloadStatus.MUXING} == ACTIVE_STATUSES


def test_groups() -> None:
    assert DOWNLOAD_GROUPS["downloading"] == {
        DownloadStatus.PENDING,
        DownloadStatus.QUEUED,
        DownloadStatus.DOWNLOADING,
        DownloadStatus.MUXING,
    }
    assert DOWNLOAD_GROUPS["seeding"] == {DownloadStatus.SEEDING}
    assert DOWNLOAD_GROUPS["completed"] == {DownloadStatus.COMPLETED}


def test_new_enums_and_names() -> None:
    assert {a.value for a in ChecksumAlgo} == {"sha256", "sha1", "md5"}
    assert {k.value for k in CollectionKind} == {"playlist", "channel"}
    assert OTHER_FOLDER == "Other"


def test_preset_values_match_the_bun_api() -> None:
    assert {p.value for p in Preset} == {"best", "2160", "1440", "1080", "720", "480", "mp3"}


@pytest.mark.parametrize(
    ("preset", "height"),
    [(Preset.BEST, None), (Preset.P2160, 2160), (Preset.P1080, 1080), (Preset.P480, 480), (Preset.MP3, None)],
)
def test_target_height(preset: Preset, height: int | None) -> None:
    assert preset.target_height == height
