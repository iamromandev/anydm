import uuid
from collections import namedtuple
from pathlib import Path
from types import SimpleNamespace
from typing import Any, get_args

import pytest
from src.core.error import Error
from src.core.success import Meta
from src.core.type import Code
from src.data.repo.catalog import address_hash
from src.data.type import BulkAction, DownloadStatus, MediaKind, Platform, Preset, SourceKind
from src.lib.identity import url_ref
from src.lib.site import error as site_error
from src.service.download.collection_service import CollectionService
from src.service.download.collection_totals import CollectionTotals
from src.service.download.control import DownloadControl
from src.service.download.disk import DiskGuard
from src.service.download.download_service import BULK_SCOPES, DownloadService, TorrentPlay
from src.service.download.live import Live, LiveStats

from tests.service.download.described_rows import a_mirror
from tests.service.download.memory import (
    MemoryFiles,
    MemoryPositions,
    RecordingHub,
    download_row,
    memory_views,
    site_detail,
)
from tests.sites import FakeSiteClient, site_info, sized

YOUTUBE = "https://youtu.be/dQw4w9WgXcQ"


class FakeDownloadRepo:
    def __init__(self) -> None:
        self.rows: dict[uuid.UUID, Any] = {}
        #: Each create, as ``{"download": …, "site": …, "filename": …, "mime_type": …}``.
        self.created: list[dict[str, Any]] = []
        #: What ``list_items`` answers, and what it was asked.
        self.items: list[tuple[str, uuid.UUID]] = []
        self.listed_statuses: Any = "not asked"
        self.listed_sort: str | None = None
        self.listed_speeds: Any = None

    def _add(self, row: Any) -> Any:
        self.rows[row.id] = row
        return row

    async def create_site(self, **added: Any) -> Any:
        self.created.append(added)
        media = dict(added["media"])
        return self._add(
            download_row(
                source_url=added["url"],
                provider=added["provider"],
                platform=Platform.SITE,
                media_kind=media.pop("kind"),
                title=media.pop("title"),
                status=added["download"]["status"],
                total_bytes=added["download"].get("total_size"),
                site_detail=site_detail(**media),
            )
        )

    async def create_direct(self, **added: Any) -> Any:
        self.created.append(added)
        return self._add(download_row(source_url=added["url"], title=added["filename"], **added["download"]))

    async def list_items(
        self, page: int, page_size: int, statuses: Any, sort: str, speeds: Any
    ) -> tuple[list[tuple[str, uuid.UUID]], Meta]:
        self.listed_statuses, self.listed_sort, self.listed_speeds = statuses, sort, speeds
        return list(self.items), Meta(page=page, page_size=page_size, total=len(self.items), total_pages=1)

    async def by_ids(self, ids: list[uuid.UUID]) -> list[Any]:
        return [self.rows[i] for i in ids if i in self.rows]

    async def by_statuses(self, statuses: list[Any]) -> list[Any]:
        wanted = set(statuses)
        return [row for row in self.rows.values() if row.status in wanted and row.parent_id is None]

    async def get_active_by_id(self, download_id: uuid.UUID) -> Any:
        row = self.rows.get(download_id)
        return None if row is None or row.deleted_at is not None else row


class FakeCollectionRepo:
    def __init__(self) -> None:
        self.rows: dict[uuid.UUID, Any] = {}
        self.calls: list[str] = []
        self.touched: set[uuid.UUID] = set()

    def known(self, collection_id: uuid.UUID) -> uuid.UUID:
        self.rows[collection_id] = SimpleNamespace(
            id=collection_id,
            mirrors=[a_mirror("https://www.youtube.com/playlist?list=PL", SourceKind.CONTENT, "Youtube")],
            media=SimpleNamespace(title="Talks", kind=MediaKind.PLAYLIST, preset=Preset.BEST),
            created_at=None,
        )
        return collection_id

    async def by_ids(self, ids: list[uuid.UUID]) -> list[Any]:
        return [self.rows[i] for i in ids if i in self.rows]

    async def get_active_by_id(self, collection_id: uuid.UUID) -> Any:
        return self.rows.get(collection_id)

    async def member_rows(self, collection_id: uuid.UUID) -> list[Any]:
        return []

    async def watched_counts(self, ids: list[uuid.UUID]) -> dict[uuid.UUID, int]:
        return dict.fromkeys(ids, 2)

    async def pause_all(self) -> tuple[list[uuid.UUID], set[uuid.UUID]]:
        self.calls.append("pause_all")
        return [], set(self.touched)

    async def resume_all(self) -> set[uuid.UUID]:
        self.calls.append("resume_all")
        return set(self.touched)


class FakeSegmentRepo:
    """Cancel clears a download's segment rows; nothing else here touches them."""

    def __init__(self) -> None:
        self.cleared: list[uuid.UUID] = []

    async def clear(self, download_id: uuid.UUID, part: Any = None) -> None:
        self.cleared.append(download_id)


class FakeTorrentService:
    """Stands in for ``TorrentService`` in the branches ``DownloadService`` delegates to."""

    def __init__(self) -> None:
        self.paused: list[uuid.UUID] = []
        self.resumed: list[uuid.UUID] = []
        self.canceled: list[tuple[uuid.UUID, bool]] = []

    async def resolve_file(self, download_id: uuid.UUID, index: int) -> tuple[Path, str, str]:
        return Path(f"/t/file{index}.mkv"), f"file{index}.mkv", "video/x-matroska"

    async def media_file_index(self, download_id: uuid.UUID, wanted: int | None = None) -> int:
        return 7 if wanted is None else wanted

    async def pause(self, download_id: uuid.UUID) -> Any:
        self.paused.append(download_id)
        return SimpleNamespace(status=DownloadStatus.PAUSED)

    async def resume(self, download_id: uuid.UUID) -> Any:
        self.resumed.append(download_id)
        return SimpleNamespace(status=DownloadStatus.DOWNLOADING)

    async def cancel(self, download_id: uuid.UUID, *, delete_files: bool = True) -> None:
        self.canceled.append((download_id, delete_files))


def _service(
    root: Path | None = None,
    *,
    disk: DiskGuard | None = None,
    client: FakeSiteClient | None = None,
) -> SimpleNamespace:
    root = root or Path("/tmp/anydm-test")
    repo, collections, torrents = FakeDownloadRepo(), FakeCollectionRepo(), FakeTorrentService()
    files, positions, hub, live = MemoryFiles(), MemoryPositions(), RecordingHub(), LiveStats()
    views = memory_views(files=files, positions=positions, live=live)
    totals = CollectionTotals(collections, hub, live)  # ty: ignore[invalid-argument-type]
    control = DownloadControl()
    service = DownloadService(
        repo=repo,  # ty: ignore[invalid-argument-type]
        collections=CollectionService(
            repo=collections,  # ty: ignore[invalid-argument-type]
            segment_repo=None,
            control=control,
            downloads_root=root,
            totals=totals,
            views=views,
        ),
        collection_repo=collections,  # ty: ignore[invalid-argument-type]
        segment_repo=FakeSegmentRepo(),  # ty: ignore[invalid-argument-type]
        files=files,  # ty: ignore[invalid-argument-type]
        positions=positions,  # ty: ignore[invalid-argument-type]
        client=client or FakeSiteClient(site_info("youtube")),
        control=control,
        hub=hub,  # ty: ignore[invalid-argument-type]
        downloads_root=root,
        torrents=torrents,  # ty: ignore[invalid-argument-type]
        views=views,
        totals=totals,
        live=live,
        disk=disk,
    )
    return SimpleNamespace(
        service=service,
        repo=repo,
        collections=collections,
        torrents=torrents,
        files=files,
        positions=positions,
        hub=hub,
        live=live,
    )


def _row(h: SimpleNamespace, **overrides: Any) -> Any:
    """A site download, filed into the fake repo."""
    fields: dict[str, Any] = {
        "platform": Platform.SITE,
        "media_kind": MediaKind.VIDEO,
        "source_url": "https://youtu.be/x",
        "title": "clip",
        "provider": "Youtube",
        "ref_id": "x",
        "site_detail": site_detail(),
    }
    fields.update(overrides)
    return h.repo._add(download_row(**fields))


def _torrent(h: SimpleNamespace, status: DownloadStatus) -> Any:
    return _row(h, platform=Platform.TORRENT, media_kind=MediaKind.FILE, site_detail=None,
                provider="torrent", ref_id="abc123", status=status)


GIB = 1024**3
_Usage = namedtuple("_Usage", ["total", "used", "free"])


def _disk(free: int, min_free: int = GIB) -> DiskGuard:
    return DiskGuard("/data", min_free, usage=lambda _path: _Usage(100 * GIB, 0, free))


# --- enqueue from a site ------------------------------------------------------


@pytest.mark.asyncio
async def test_enqueue_media_writes_a_pending_site_download() -> None:
    h = _service()
    schema = await h.service.enqueue_media(YOUTUBE, Preset.P1080)

    assert schema.site is not None and schema.site.extractor == "Youtube"  # the site reaches the UI too
    created = h.repo.created[0]
    media = created["media"]
    assert (created["download"]["status"], media["kind"], media["preset"]) == (
        DownloadStatus.PENDING,
        MediaKind.VIDEO,
        Preset.P1080,
    )
    assert created["provider"] == "Youtube"
    # Stored under the site's own page for the video, not the link as pasted.
    assert created["url"] == site_info("youtube").webpage_url
    assert h.hub.named("download")[0]["id"] == str(schema.id)


@pytest.mark.asyncio
async def test_enqueue_media_stores_the_plan_s_format_ids_and_names_the_file() -> None:
    h = _service()
    await h.service.enqueue_media(YOUTUBE, Preset.P1080)

    created = h.repo.created[0]
    assert (created["media"]["video_format"], created["media"]["audio_format"]) == ("137", "140")
    assert created["media"]["title"] == site_info("youtube").title
    assert created["filename"].endswith("_1080p.mp4")
    assert created["mime_type"] == "video/mp4"
    assert created["download"]["total_size"] == 80_911_999 + 3_449_447


@pytest.mark.asyncio
async def test_a_combined_format_is_one_part() -> None:
    h = _service(client=FakeSiteClient(site_info("vimeo")))
    await h.service.enqueue_media("http://vimeo.com/75629013", Preset.BEST)

    created = h.repo.created[0]
    assert (created["media"]["video_format"], created["media"]["audio_format"], created["provider"]) == (
        "http-1080p",
        None,
        "Vimeo",
    )
    assert created["download"]["total_size"] is None


@pytest.mark.asyncio
async def test_an_estimated_size_is_not_stored_as_the_total() -> None:
    # A wrong total would stall or overshoot the progress bar; the probe will
    # learn the real one. The estimate still counts for the disk guard.
    h = _service(client=FakeSiteClient(site_info("twitter")))
    await h.service.enqueue_media("https://twitter.com/x/status/1", Preset.BEST)

    assert h.repo.created[0]["download"]["total_size"] is None


@pytest.mark.asyncio
async def test_an_mp3_from_an_audio_site() -> None:
    h = _service(client=FakeSiteClient(site_info("soundcloud")))
    await h.service.enqueue_media("http://soundcloud.com/x/y", Preset.MP3)

    created = h.repo.created[0]
    assert (created["media"]["kind"], created["media"]["video_format"], created["media"]["audio_format"]) == (
        MediaKind.AUDIO,
        None,
        "http_mp3_0_0",
    )
    assert created["filename"].endswith(".mp3")


@pytest.mark.asyncio
async def test_a_taller_preset_than_available_falls_back_to_the_tallest() -> None:
    h = _service(client=FakeSiteClient(site_info("twitter")))
    await h.service.enqueue_media("https://twitter.com/x/status/1", Preset.P2160)

    assert h.repo.created[0]["media"]["video_format"] == "http-2176"


@pytest.mark.asyncio
async def test_a_site_with_only_streaming_formats_is_queued_for_the_fragment_path() -> None:
    h = _service(client=FakeSiteClient(site_info("dailymotion")))

    await h.service.enqueue_media("https://dailymotion.com/video/x", Preset.BEST)

    created = h.repo.created[0]
    assert created["media"]["video_format"] == "hls-1080"
    assert created["filename"].endswith(".mp4")


@pytest.mark.asyncio
async def test_the_tallest_format_wins_even_when_it_is_streaming_only() -> None:
    # Reddit's tallest is HLS-only at 640p; its HTTPS formats stop at 480p.
    h = _service(client=FakeSiteClient(site_info("reddit")))
    await h.service.enqueue_media("https://reddit.com/r/x", Preset.BEST)

    site = h.repo.created[0]["media"]
    assert (site["video_format"], site["audio_format"]) == ("hls-1875", "dash-AUDIO-1")


@pytest.mark.asyncio
async def test_a_live_stream_is_refused() -> None:
    h = _service(client=FakeSiteClient(site_info("twitch", is_live=True)))

    with pytest.raises(Error) as caught:
        await h.service.enqueue_media("https://twitch.tv/x", Preset.BEST)

    assert caught.value.code == Code.UNPROCESSABLE_ENTITY
    assert h.repo.created == []


@pytest.mark.asyncio
async def test_a_preset_the_site_cannot_satisfy_is_refused() -> None:
    h = _service(client=FakeSiteClient(site_info("soundcloud")))

    with pytest.raises(Error) as caught:
        await h.service.enqueue_media("http://soundcloud.com/x/y", Preset.P1080)

    assert caught.value.code == Code.UNPROCESSABLE_ENTITY
    assert h.repo.created == []


@pytest.mark.asyncio
async def test_an_extraction_failure_reaches_the_caller() -> None:
    client = FakeSiteClient(site_info("youtube"), fail=site_error.unsupported_url("https://example.test/x"))
    h = _service(client=client)

    with pytest.raises(Error) as caught:
        await h.service.enqueue_media("https://example.test/x", Preset.BEST)

    assert caught.value.code == Code.BAD_REQUEST
    assert h.repo.created == []


@pytest.mark.asyncio
async def test_enqueue_url_writes_a_direct_download_with_its_one_file() -> None:
    h = _service()
    schema = await h.service.enqueue_url("https://cdn.test/files/report.pdf")

    created = h.repo.created[0]
    assert (created["url"], created["download"]["status"]) == ("https://cdn.test/files/report.pdf", DownloadStatus.PENDING)
    assert created["filename"] == "report.pdf"
    assert schema.site is None and schema.media_kind == MediaKind.FILE


@pytest.mark.asyncio
async def test_a_direct_download_is_identified_by_its_normalized_address() -> None:
    """Two spellings of one address are one identity: the case of the host and tracking parameters do not count."""
    h = _service()
    await h.service.enqueue_url("https://CDN.test/files/report.pdf?utm_source=mail")
    await h.service.enqueue_url("https://cdn.test/files/report.pdf")

    first, second = (created["url"] for created in h.repo.created)
    assert address_hash(first) == address_hash(second) == url_ref("https://cdn.test/files/report.pdf")


@pytest.mark.asyncio
async def test_enqueue_url_rejects_a_non_http_scheme() -> None:
    with pytest.raises(Error) as caught:
        await _service().service.enqueue_url("file:///etc/passwd")
    assert caught.value.code == 400


# --- disk space -------------------------------------------------------------


@pytest.mark.asyncio
async def test_enqueue_url_refuses_when_free_space_is_below_the_minimum() -> None:
    h = _service(disk=_disk(free=GIB // 2))

    with pytest.raises(Error) as caught:
        await h.service.enqueue_url("https://cdn.test/files/report.pdf")

    assert caught.value.code == Code.INSUFFICIENT_STORAGE
    assert h.repo.created == []


@pytest.mark.asyncio
async def test_enqueue_url_needs_only_the_minimum_since_its_size_is_not_known_yet() -> None:
    h = _service(disk=_disk(free=GIB + 1))

    await h.service.enqueue_url("https://cdn.test/files/huge.iso")

    assert len(h.repo.created) == 1


@pytest.mark.asyncio
async def test_enqueue_media_refuses_a_plan_that_would_not_fit() -> None:
    # 4 GiB of streams plus the 1 GiB minimum is 5 GiB; 4.5 GiB is free.
    info = sized(site_info("youtube"), {"137": 3 * GIB, "140": GIB})
    h = _service(disk=_disk(free=9 * GIB // 2), client=FakeSiteClient(info))

    with pytest.raises(Error) as caught:
        await h.service.enqueue_media(YOUTUBE, Preset.P1080)

    assert caught.value.code == Code.INSUFFICIENT_STORAGE
    assert h.repo.created == []


@pytest.mark.asyncio
async def test_enqueue_media_accepts_a_plan_that_fits() -> None:
    info = sized(site_info("youtube"), {"137": 3 * GIB, "140": GIB})
    h = _service(disk=_disk(free=5 * GIB), client=FakeSiteClient(info))

    await h.service.enqueue_media(YOUTUBE, Preset.P1080)

    assert h.repo.created[0]["download"]["total_size"] == 4 * GIB


@pytest.mark.asyncio
async def test_an_estimate_still_counts_for_the_disk_guard() -> None:
    # X's 720p is estimated at 862,240 bytes; with a 1 GiB minimum, 1 GiB plus
    # a few bytes is not enough.
    h = _service(disk=_disk(free=GIB + 1000), client=FakeSiteClient(site_info("twitter")))

    with pytest.raises(Error) as caught:
        await h.service.enqueue_media("https://twitter.com/x/status/1", Preset.BEST)

    assert caught.value.code == Code.INSUFFICIENT_STORAGE


@pytest.mark.asyncio
async def test_an_unknown_size_needs_only_the_minimum() -> None:
    h = _service(disk=_disk(free=GIB + 1), client=FakeSiteClient(site_info("vimeo")))

    await h.service.enqueue_media("http://vimeo.com/75629013", Preset.BEST)

    assert len(h.repo.created) == 1


# --- the list -----------------------------------------------------------------


@pytest.mark.asyncio
async def test_the_list_keeps_the_view_order_across_downloads_and_collections() -> None:
    h = _service()
    one, two = _row(h), _row(h)
    collection_id = h.collections.known(uuid.uuid4())
    h.repo.items = [("download", two.id), ("collection", collection_id), ("download", one.id)]

    items, _ = await h.service.list_items(1, 50)

    assert [(item.type, item.id) for item in items] == [
        ("download", two.id),
        ("collection", collection_id),
        ("download", one.id),
    ]
    assert items[1].counts.watched == 2


@pytest.mark.asyncio
async def test_the_list_hands_the_repo_the_live_speeds() -> None:
    h = _service()
    moving = uuid.uuid4()
    h.live.set(moving, Live(speed_bps=9))

    await h.service.list_items(1, 50, sort="-speed_bps")

    assert h.repo.listed_speeds == {moving: 9}
    assert h.repo.listed_sort == "-speed_bps"


@pytest.mark.asyncio
async def test_listing_a_group_asks_for_the_statuses_it_means() -> None:
    """The sidebar's "Downloading" includes queued rows; the orphan check does not."""
    h = _service()

    await h.service.list_items(1, 10, group="downloading")

    assert set(h.repo.listed_statuses) == {
        DownloadStatus.PENDING,
        DownloadStatus.QUEUED,
        DownloadStatus.DOWNLOADING,
        DownloadStatus.MUXING,
    }


@pytest.mark.asyncio
async def test_listing_everything_asks_for_no_statuses_and_defaults_to_newest_first() -> None:
    h = _service()

    await h.service.list_items(1, 10)

    assert h.repo.listed_statuses is None
    assert h.repo.listed_sort == "-created_at"


@pytest.mark.asyncio
async def test_a_sort_on_a_column_not_offered_is_refused() -> None:
    """The route types this too; this guards against a caller inside the process
    reaching the database's ORDER BY with something arbitrary."""
    h = _service()

    for attempt in ("db_password", "-nonsense", ""):
        with pytest.raises(Error) as caught:
            await h.service.list_items(1, 10, sort=attempt)
        assert caught.value.code == 400


@pytest.mark.asyncio
async def test_listing_an_unknown_group_is_rejected() -> None:
    with pytest.raises(Error) as caught:
        await _service().service.list_items(1, 10, group="nonsense")
    assert caught.value.code == 400


@pytest.mark.asyncio
async def test_a_list_item_carries_its_files_playback() -> None:
    h = _service()
    first, second = _row(h), _row(h)
    single = await h.files.set_single(first.id, path="a.mp4", mime_type="video/mp4")
    await h.positions.save(single.id, position_seconds=10.0, duration_seconds=100.0, watched=False)
    h.repo.items = [("download", first.id), ("download", second.id)]

    items, _ = await h.service.list_items(1, 20)

    assert items[0].files[0].playback is not None
    assert items[0].files[0].playback.position_seconds == 10.0
    assert items[1].files == []


# --- files --------------------------------------------------------------------


@pytest.mark.asyncio
async def test_resolve_file_rejects_an_incomplete_download(tmp_path: Path) -> None:
    h = _service(tmp_path)
    row = _row(h, status=DownloadStatus.DOWNLOADING)

    with pytest.raises(Error) as caught:
        await h.service.resolve_file(row.id, None)
    assert caught.value.code == 409


@pytest.mark.asyncio
async def test_a_finished_single_file_is_served_from_its_folder(tmp_path: Path) -> None:
    h = _service(tmp_path)
    row = _row(h, status=DownloadStatus.COMPLETED)
    folder = tmp_path / str(row.id)
    folder.mkdir()
    (folder / "clip.mp4").write_bytes(b"x")
    await h.files.set_single(row.id, path="clip.mp4", mime_type="video/mp4")

    path, filename, media_type = await h.service.resolve_file(row.id, 0)

    assert path == (folder / "clip.mp4").resolve()
    assert (filename, media_type) == ("clip.mp4", "video/mp4")


@pytest.mark.asyncio
async def test_resolve_file_404s_when_the_row_is_gone(tmp_path: Path) -> None:
    with pytest.raises(Error) as caught:
        await _service(tmp_path).service.resolve_file(uuid.uuid4(), None)
    assert caught.value.code == 404


@pytest.mark.asyncio
async def test_resolve_file_404s_when_the_file_vanished(tmp_path: Path) -> None:
    h = _service(tmp_path)
    row = _row(h, status=DownloadStatus.COMPLETED)
    await h.files.set_single(row.id, path="gone.mp4", mime_type="video/mp4")

    with pytest.raises(Error) as caught:
        await h.service.resolve_file(row.id, None)
    assert caught.value.code == 404


@pytest.mark.asyncio
async def test_a_download_has_no_file_at_another_index(tmp_path: Path) -> None:
    h = _service(tmp_path)
    row = _row(h, status=DownloadStatus.COMPLETED)

    with pytest.raises(Error) as caught:
        await h.service.resolve_file(row.id, 2)
    assert caught.value.code == 404


@pytest.mark.asyncio
@pytest.mark.parametrize("status", [DownloadStatus.SEEDING, DownloadStatus.COMPLETED])
async def test_resolve_file_hands_a_torrent_to_the_torrent_service(status: DownloadStatus) -> None:
    h = _service()
    row = _torrent(h, status)

    assert await h.service.resolve_file(row.id, 3) == (Path("/t/file3.mkv"), "file3.mkv", "video/x-matroska")


@pytest.mark.asyncio
async def test_the_media_file_of_a_download_is_its_file(tmp_path: Path) -> None:
    """What Play on a finished card reads from disk (#94)."""
    h = _service(tmp_path)
    row = _row(h, status=DownloadStatus.COMPLETED)
    folder = tmp_path / str(row.id)
    folder.mkdir()
    (folder / "clip.mp4").write_bytes(b"x")
    await h.files.set_single(row.id, path="clip.mp4", mime_type="video/mp4")

    assert await h.service.resolve_media_file(row.id, None) == (
        (folder / "clip.mp4").resolve(),
        "clip.mp4",
        None,
    )


@pytest.mark.asyncio
async def test_the_media_file_of_a_torrent_is_the_one_asked_for_or_its_largest() -> None:
    h = _service()
    row = _torrent(h, DownloadStatus.SEEDING)

    assert await h.service.resolve_media_file(row.id, 3) == (Path("/t/file3.mkv"), "file3.mkv", 3)
    assert await h.service.resolve_media_file(row.id, None) == (Path("/t/file7.mkv"), "file7.mkv", 7)


@pytest.mark.asyncio
async def test_a_download_s_subtitle_files_are_its_neighbours_on_disk(tmp_path: Path) -> None:
    """#101: beside it, or in a subtitles folder there; never another film's."""
    h = _service(tmp_path)
    row = _row(h, status=DownloadStatus.COMPLETED)
    folder = tmp_path / str(row.id)
    (folder / "Subs").mkdir(parents=True)
    for name in ("Movie.mkv", "Movie.en.srt", "Subs/Movie.fr.srt", "Other.en.srt"):
        (folder / name).write_bytes(b"x")
    await h.files.set_single(row.id, path="Movie.mkv", mime_type="video/x-matroska")

    found = await h.service.subtitle_files(row.id, None)

    resolved = folder.resolve()
    assert [(sidecar.path, source) for sidecar, source in found] == [
        ("Movie.en.srt", resolved / "Movie.en.srt"),
        ("Subs/Movie.fr.srt", resolved / "Subs" / "Movie.fr.srt"),
    ]


@pytest.mark.asyncio
async def test_an_unfinished_download_has_no_subtitle_files_yet() -> None:
    h = _service()
    row = _row(h, status=DownloadStatus.DOWNLOADING)

    assert await h.service.subtitle_files(row.id, None) == []


# --- torrents play while they download (#95) ----------------------------------


@pytest.mark.asyncio
async def test_a_downloading_torrent_plays_from_its_torrent() -> None:
    h = _service()
    row = _torrent(h, DownloadStatus.DOWNLOADING)

    assert await h.service.torrent_play(row.id, None) == TorrentPlay("abc123", 7)
    assert await h.service.torrent_play(row.id, 2) == TorrentPlay("abc123", 2)
    assert h.torrents.resumed == []


@pytest.mark.asyncio
async def test_a_paused_torrent_is_resumed_to_play() -> None:
    """A stream from a paused torrent would stall."""
    h = _service()
    row = _torrent(h, DownloadStatus.PAUSED)

    assert await h.service.torrent_play(row.id, None) == TorrentPlay("abc123", 7)
    assert h.torrents.resumed == [row.id]


@pytest.mark.asyncio
@pytest.mark.parametrize("status", [DownloadStatus.COMPLETED, DownloadStatus.SEEDING])
async def test_a_finished_torrent_plays_from_disk(status: DownloadStatus) -> None:
    h = _service()
    assert await h.service.torrent_play(_torrent(h, status).id, None) is None


@pytest.mark.asyncio
async def test_a_download_that_is_not_a_torrent_plays_from_disk() -> None:
    h = _service()
    assert await h.service.torrent_play(_row(h, status=DownloadStatus.DOWNLOADING).id, None) is None


@pytest.mark.asyncio
async def test_a_failed_torrent_has_nothing_to_play() -> None:
    h = _service()
    with pytest.raises(Error) as caught:
        await h.service.torrent_play(_torrent(h, DownloadStatus.FAILED).id, None)
    assert caught.value.code == 409


# --- pause, resume, cancel ----------------------------------------------------


@pytest.mark.asyncio
async def test_pause_stops_a_running_download_and_stills_its_numbers() -> None:
    h = _service()
    row = _row(h, status=DownloadStatus.DOWNLOADING)
    h.live.set(row.id, Live(speed_bps=5))

    result = await h.service.pause(row.id)

    assert result.status == DownloadStatus.PAUSED
    assert h.service._control.is_stopping(row.id) is True
    assert h.live.get(row.id) == Live()
    assert h.hub.named("download")[0]["status"] == "paused"


@pytest.mark.asyncio
async def test_pause_also_works_on_a_queued_download() -> None:
    h = _service()
    assert (await h.service.pause(_row(h, status=DownloadStatus.PENDING).id)).status == DownloadStatus.PAUSED


@pytest.mark.asyncio
async def test_pause_rejects_a_completed_download() -> None:
    h = _service()
    with pytest.raises(Error) as caught:
        await h.service.pause(_row(h, status=DownloadStatus.COMPLETED).id)
    assert caught.value.code == 409


@pytest.mark.asyncio
async def test_pausing_a_collection_video_refreshes_its_collection() -> None:
    h = _service()
    collection_id = h.collections.known(uuid.uuid4())
    row = _row(h, status=DownloadStatus.DOWNLOADING, parent_id=collection_id)

    await h.service.pause(row.id)

    assert [data["id"] for data in h.hub.named("collection")] == [str(collection_id)]


@pytest.mark.asyncio
async def test_resume_requeues_a_paused_download() -> None:
    h = _service()
    row = _row(h, status=DownloadStatus.PAUSED)
    h.service._control.request_stop(row.id)

    result = await h.service.resume(row.id)

    assert result.status == DownloadStatus.PENDING
    assert h.service._control.is_stopping(row.id) is False


@pytest.mark.asyncio
async def test_resuming_a_download_a_worker_still_holds_lets_that_worker_stop_first() -> None:
    h = _service()
    row = _row(h, status=DownloadStatus.PAUSED)
    h.service._control.hold(row.id)
    h.service._control.request_stop(row.id)

    result = await h.service.resume(row.id)

    # Pending for the next claim, which skips it until the holder has stopped and let go.
    assert result.status == DownloadStatus.PENDING
    assert h.service._control.is_stopping(row.id) is True


@pytest.mark.asyncio
async def test_resume_clears_the_failure_state() -> None:
    h = _service()
    row = _row(h, status=DownloadStatus.FAILED, error="boom", error_code="dependency_failure", attempts=3)

    result = await h.service.resume(row.id)

    assert (result.status, result.error, result.error_code, result.attempts) == (
        DownloadStatus.PENDING,
        None,
        None,
        0,
    )


@pytest.mark.asyncio
async def test_resume_rejects_a_running_download() -> None:
    h = _service()
    with pytest.raises(Error) as caught:
        await h.service.resume(_row(h, status=DownloadStatus.DOWNLOADING).id)
    assert caught.value.code == 409


@pytest.mark.asyncio
async def test_cancel_stops_the_download_and_removes_its_work_files(tmp_path: Path) -> None:
    h = _service(tmp_path)
    row = _row(h, status=DownloadStatus.DOWNLOADING)
    (tmp_path / str(row.id)).mkdir()
    (tmp_path / str(row.id) / "video.part").write_bytes(b"x")

    await h.service.cancel(row.id)

    assert not (tmp_path / str(row.id)).exists()
    assert row.status == DownloadStatus.CANCELLED
    assert row.deleted_at is not None


@pytest.mark.asyncio
async def test_cancel_can_keep_the_file_of_a_finished_download(tmp_path: Path) -> None:
    h = _service(tmp_path)
    row = _row(h, status=DownloadStatus.COMPLETED)
    folder = tmp_path / str(row.id)
    folder.mkdir()
    (folder / "clip.mp4").write_bytes(b"x")
    await h.files.set_single(row.id, path="clip.mp4", mime_type="video/mp4")

    await h.service.cancel(row.id, delete_files=False)

    assert (folder / "clip.mp4").read_bytes() == b"x"
    assert row.status == DownloadStatus.CANCELLED


@pytest.mark.asyncio
async def test_cancel_with_files_takes_a_finished_download_s_file_and_its_subtitles(tmp_path: Path) -> None:
    h = _service(tmp_path)
    collection_id = h.collections.known(uuid.uuid4())
    row = _row(h, status=DownloadStatus.COMPLETED, parent_id=collection_id)
    folder = tmp_path / "Talks [PL]"
    folder.mkdir()
    for name in ("02_Talk_720p.mp4", "02_Talk_720p.en.vtt", "03_Other_720p.mp4"):
        (folder / name).write_bytes(b"x")
    await h.files.set_single(row.id, path="02_Talk_720p.mp4", mime_type="video/mp4")

    await h.service.cancel(row.id, delete_files=True)

    assert sorted(p.name for p in folder.iterdir()) == ["03_Other_720p.mp4"]


@pytest.mark.asyncio
async def test_cancel_refuses_to_keep_the_files_of_an_unfinished_download() -> None:
    """A `.part` is meaningless once its row and byte watermarks are gone."""
    h = _service()
    row = _row(h, status=DownloadStatus.DOWNLOADING)

    with pytest.raises(Error) as caught:
        await h.service.cancel(row.id, delete_files=False)

    assert caught.value.code == 409
    assert row.status == DownloadStatus.DOWNLOADING


@pytest.mark.asyncio
async def test_cancel_404s_on_an_unknown_download() -> None:
    with pytest.raises(Error) as caught:
        await _service().service.cancel(uuid.uuid4())
    assert caught.value.code == 404


@pytest.mark.asyncio
async def test_pause_resume_and_cancel_delegate_a_torrent() -> None:
    h = _service()
    row = _torrent(h, DownloadStatus.DOWNLOADING)

    assert (await h.service.pause(row.id)).status == DownloadStatus.PAUSED
    assert (await h.service.resume(row.id)).status == DownloadStatus.DOWNLOADING
    row.status = DownloadStatus.SEEDING
    await h.service.cancel(row.id, delete_files=False)

    assert h.torrents.paused == [row.id]
    assert h.torrents.resumed == [row.id]
    # Keeping a torrent's files is the engine's decision to make.
    assert h.torrents.canceled == [(row.id, False)]


# --- playback (#96) -----------------------------------------------------------


@pytest.mark.asyncio
async def test_playback_is_saved_on_the_download_s_file() -> None:
    h = _service()
    row = _row(h, status=DownloadStatus.COMPLETED)
    single = await h.files.set_single(row.id, path="a.mp4", mime_type="video/mp4")

    saved = await h.service.save_playback(row.id, None, position_seconds=61.5, duration_seconds=1300.0)

    assert (saved.position_seconds, saved.watched) == (61.5, False)
    assert h.positions.by_file[single.id].position_seconds == 61.5


@pytest.mark.asyncio
async def test_stopping_near_the_end_marks_it_watched_and_clears_where_to_resume() -> None:
    h = _service()
    row = _row(h, status=DownloadStatus.COMPLETED)
    await h.files.set_single(row.id, path="a.mp4", mime_type="video/mp4")

    saved = await h.service.save_playback(row.id, 0, position_seconds=1275.0, duration_seconds=1300.0)

    assert (saved.position_seconds, saved.watched) == (0.0, True)


@pytest.mark.asyncio
async def test_a_watched_file_stays_watched_when_played_again() -> None:
    h = _service()
    row = _row(h, status=DownloadStatus.COMPLETED)
    await h.files.set_single(row.id, path="a.mp4", mime_type="video/mp4")
    await h.service.save_playback(row.id, 0, position_seconds=1290.0, duration_seconds=1300.0)

    again = await h.service.save_playback(row.id, 0, position_seconds=40.0, duration_seconds=1300.0)

    assert (again.position_seconds, again.watched) == (40.0, True)


@pytest.mark.asyncio
async def test_playback_for_a_file_that_isn_t_there_is_a_404() -> None:
    h = _service()
    row = _row(h, status=DownloadStatus.COMPLETED)

    with pytest.raises(Error) as caught:
        await h.service.save_playback(row.id, 3, position_seconds=1.0, duration_seconds=2.0)
    assert caught.value.code == 404
    with pytest.raises(Error) as caught:
        await h.service.save_playback(uuid.uuid4(), None, position_seconds=1.0, duration_seconds=2.0)
    assert caught.value.code == 404


# --- bulk actions -------------------------------------------------------------


def _bulk_service(tmp_path: Path) -> SimpleNamespace:
    h = _service(tmp_path)
    for status in (
        DownloadStatus.PENDING,
        DownloadStatus.DOWNLOADING,
        DownloadStatus.PAUSED,
        DownloadStatus.SEEDING,
        DownloadStatus.COMPLETED,
        DownloadStatus.FAILED,
    ):
        if status == DownloadStatus.SEEDING:
            _torrent(h, status)
        else:
            _row(h, status=status, platform=Platform.DIRECT, media_kind=MediaKind.FILE, site_detail=None)
    return h


def _statuses(h: SimpleNamespace) -> list[DownloadStatus]:
    return sorted(row.status for row in h.repo.rows.values())


@pytest.mark.asyncio
async def test_pause_all_takes_only_what_can_be_paused(tmp_path: Path) -> None:
    h = _bulk_service(tmp_path)

    affected = await h.service.bulk("pause_all")

    # pending, downloading and the seeding torrent; not paused, complete or failed.
    assert affected == 3
    assert "pause_all" in h.collections.calls
    assert _statuses(h).count(DownloadStatus.PAUSED) == 3
    assert DownloadStatus.COMPLETED in _statuses(h)


@pytest.mark.asyncio
async def test_resume_all_takes_only_what_can_be_resumed(tmp_path: Path) -> None:
    h = _bulk_service(tmp_path)

    affected = await h.service.bulk("resume_all")

    # The paused one and the failed one, both back to pending.
    assert affected == 2
    assert _statuses(h).count(DownloadStatus.PENDING) == 3


@pytest.mark.asyncio
async def test_a_collection_touched_by_a_sweep_is_refreshed_once(tmp_path: Path) -> None:
    h = _bulk_service(tmp_path)
    collection_id = h.collections.known(uuid.uuid4())
    h.collections.touched = {collection_id}

    affected = await h.service.bulk("pause_all")

    assert affected == 4
    assert [data["id"] for data in h.hub.named("collection")] == [str(collection_id)]


@pytest.mark.asyncio
async def test_clear_finished_keeps_a_finished_file_but_not_a_failed_one(tmp_path: Path) -> None:
    """Nothing worth keeping survives a failure, and keeping it would 409."""
    h = _bulk_service(tmp_path)
    failed = next(row for row in h.repo.rows.values() if row.status == DownloadStatus.FAILED)
    (tmp_path / str(failed.id)).mkdir()
    finished = next(row for row in h.repo.rows.values() if row.status == DownloadStatus.COMPLETED)
    folder = tmp_path / str(finished.id)
    folder.mkdir()
    (folder / "f.bin").write_bytes(b"x")
    await h.files.set_single(finished.id, path="f.bin", mime_type=None)

    affected = await h.service.bulk("clear_finished")

    assert affected == 2
    assert (folder / "f.bin").exists()
    assert not (tmp_path / str(failed.id)).exists()


@pytest.mark.asyncio
async def test_clear_finished_can_take_the_files_too(tmp_path: Path) -> None:
    h = _bulk_service(tmp_path)
    finished = next(row for row in h.repo.rows.values() if row.status == DownloadStatus.COMPLETED)
    folder = tmp_path / str(finished.id)
    folder.mkdir()
    (folder / "f.bin").write_bytes(b"x")
    await h.files.set_single(finished.id, path="f.bin", mime_type=None)

    await h.service.bulk("clear_finished", delete_files=True)

    assert not (folder / "f.bin").exists()


@pytest.mark.asyncio
async def test_one_row_refusing_does_not_end_the_sweep(tmp_path: Path) -> None:
    h = _bulk_service(tmp_path)
    doomed = next(row for row in h.repo.rows.values() if row.status == DownloadStatus.DOWNLOADING)

    async def _explode(*_args: Any, **_kwargs: Any) -> None:
        raise Error.conflict(message="no")

    doomed.save = _explode

    # The other two still paused; the count reports what actually happened.
    assert await h.service.bulk("pause_all") == 2


@pytest.mark.asyncio
async def test_an_unknown_bulk_action_is_refused(tmp_path: Path) -> None:
    with pytest.raises(Error) as caught:
        await _bulk_service(tmp_path).service.bulk("delete_everything")
    assert caught.value.code == 400


def test_every_bulk_action_the_api_accepts_has_a_scope() -> None:
    """The names live in the type module; what they mean lives in the service."""
    assert set(get_args(BulkAction)) == set(BULK_SCOPES)
