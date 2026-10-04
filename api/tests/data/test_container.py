"""A collection is a download whose children are downloads; there is no separate model."""

from typing import Any

import pytest
from src.data.db import model
from src.data.db.model import Download, Queue, SiteDetail
from src.data.type import DownloadStatus, MediaKind, Preset
from src.service.download.paths import collection_folder


async def a_queue() -> Queue:
    queue, _ = await Queue.get_or_create(slug="main", defaults={"name": "Main", "is_default": True})
    return queue


async def a_download(**fields: Any) -> Download:
    base: dict[str, Any] = {
        "status": DownloadStatus.PENDING,
        "queue": await a_queue(),
    }
    base.update(fields)
    return await Download.create(**base)


def test_a_playlist_and_a_channel_are_kinds_of_download() -> None:
    assert (MediaKind.PLAYLIST, MediaKind.CHANNEL) == ("playlist", "channel")


@pytest.mark.asyncio
async def test_a_collection_holds_its_videos_as_children(sqlite: None) -> None:
    collection = await a_download()
    first, second = await a_download(parent=collection), await a_download(parent=collection)

    children = await Download.filter(parent=collection).order_by("created_at")

    assert [child.id for child in children] == [first.id, second.id]
    assert all(child.parent_id == collection.id for child in children)
    assert (await Download.get(id=collection.id)).parent_id is None


@pytest.mark.asyncio
async def test_a_collection_keeps_its_quality_ceiling_in_a_site_detail(sqlite: None) -> None:
    collection = await a_download()

    await SiteDetail.create(download=collection, preset=Preset.P1080)

    detail = await SiteDetail.get(download=collection)
    assert (detail.preset, detail.video_format, detail.audio_format) == (Preset.P1080, None, None)


def test_a_collection_derives_its_directory() -> None:
    """No stored path: the same row always names the same directory."""
    assert collection_folder("Talks", "PL1") == "Talks [PL1]"


@pytest.mark.asyncio
async def test_removing_a_collection_removes_its_videos(sqlite: None) -> None:
    collection = await a_download()
    await a_download(parent=collection)
    standalone = await a_download()

    await collection.delete()

    assert await Download.filter(parent_id=collection.id).count() == 0
    assert await Download.filter(id=standalone.id).count() == 1


@pytest.mark.asyncio
async def test_a_child_can_be_found_through_its_parent(sqlite: None) -> None:
    collection = await a_download()
    video = await a_download(parent=collection)

    loaded = await Download.get(id=video.id).prefetch_related("parent")

    assert loaded.parent is not None and loaded.parent.id == collection.id
    assert [child.id for child in await collection.children.all()] == [video.id]


def test_there_is_no_collection_model() -> None:
    assert not hasattr(model, "Collection")
