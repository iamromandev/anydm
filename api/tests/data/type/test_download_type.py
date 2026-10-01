from src.data.type import (
    ACTIVE_STATUSES,
    DOWNLOAD_GROUPS,
    MAIN_QUEUE,
    OTHER_CATEGORY,
    ChecksumAlgo,
    CollectionKind,
    DownloadStatus,
    MediaKind,
    Platform,
    SegmentPart,
)


def test_platform_is_how_bytes_arrive() -> None:
    assert [p.value for p in Platform] == ["site", "direct", "torrent"]


def test_media_kind_is_what_they_are() -> None:
    assert [k.value for k in MediaKind] == ["video", "audio", "file"]


def test_segment_parts_are_the_workers_part_names() -> None:
    assert [p.value for p in SegmentPart] == ["file", "video", "audio"]


def test_terminal_statuses() -> None:
    assert {s for s in DownloadStatus if s.is_terminal} == {
        DownloadStatus.COMPLETE,
        DownloadStatus.FAILED,
        DownloadStatus.CANCELED,
    }


def test_active_statuses_are_a_worker_mid_flight() -> None:
    assert {DownloadStatus.DOWNLOADING, DownloadStatus.MUXING} == ACTIVE_STATUSES


def test_groups() -> None:
    assert DOWNLOAD_GROUPS["downloading"] == {
        DownloadStatus.PENDING,
        DownloadStatus.DOWNLOADING,
        DownloadStatus.MUXING,
    }


def test_new_enums_and_names() -> None:
    assert {a.value for a in ChecksumAlgo} == {"sha256", "sha1", "md5"}
    assert {k.value for k in CollectionKind} == {"playlist", "channel"}
    assert (MAIN_QUEUE, OTHER_CATEGORY) == ("Main", "Other")
