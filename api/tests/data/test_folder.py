"""A folder is where downloads are filed; a download's ``path`` is where its files landed."""

from typing import Any

import pytest
from src.data.db.model import Collection, Download, Folder, Queue
from src.data.type import CollectionKind, DownloadStatus, MediaKind, Platform, Preset
from tortoise.exceptions import IntegrityError


async def a_folder(name: str = "Video", slug: str = "video") -> Folder:
    return await Folder.create(name=name, slug=slug, save_dir=name, extensions=["mp4"])


async def a_download(**fields: Any) -> Download:
    return await Download.create(
        source_url="https://example.com/a.mp4",
        platform=Platform.DIRECT,
        media_kind=MediaKind.VIDEO,
        status=DownloadStatus.PENDING,
        queue=await Queue.create(name="Main"),
        **fields,
    )


@pytest.mark.asyncio
async def test_a_folder_has_a_unique_slug(sqlite: None) -> None:
    await a_folder()

    with pytest.raises(IntegrityError):
        await Folder.create(name="Videos", slug="video", save_dir="Videos")


@pytest.mark.asyncio
async def test_a_folder_has_a_unique_name(sqlite: None) -> None:
    await a_folder()

    with pytest.raises(IntegrityError):
        await Folder.create(name="Video", slug="video-2", save_dir="Video")


@pytest.mark.asyncio
async def test_a_download_is_filed_in_a_folder_and_records_its_own_path(sqlite: None) -> None:
    folder = await a_folder()

    row = await a_download(folder=folder, path="Video/a")

    loaded = await Download.get(id=row.id).prefetch_related("folder")
    assert loaded.folder is not None and loaded.folder.slug == "video"
    assert loaded.path == "Video/a"


@pytest.mark.asyncio
async def test_a_download_needs_no_folder(sqlite: None) -> None:
    row = await a_download()

    assert (await Download.get(id=row.id)).folder_id is None
    assert row.path is None


@pytest.mark.asyncio
async def test_deleting_a_folder_keeps_its_downloads_and_their_path(sqlite: None) -> None:
    folder = await a_folder()
    row = await a_download(folder=folder, path="Video/a")

    await folder.delete()

    kept = await Download.get(id=row.id)
    assert kept.folder_id is None
    assert kept.path == "Video/a"


async def a_collection(**fields: Any) -> Collection:
    return await Collection.create(
        kind=CollectionKind.PLAYLIST, extractor="YoutubeTab", ref_id="PL1", title="Talks", preset=Preset.BEST, **fields
    )


@pytest.mark.asyncio
async def test_a_collection_is_filed_in_a_folder_or_none(sqlite: None) -> None:
    folder = await a_folder()

    filed, unfiled = await a_collection(folder=folder), await Collection.create(
        kind=CollectionKind.CHANNEL, extractor="YoutubeTab", ref_id="UC1", preset=Preset.BEST
    )

    assert await Collection.filter(id=filed.id).values_list("folder_id", flat=True) == [folder.id]
    assert await Collection.filter(id=unfiled.id).values_list("folder_id", flat=True) == [None]


@pytest.mark.asyncio
async def test_a_folder_that_collections_use_is_not_deleted(sqlite: None) -> None:
    """The collection's directory is the folder's ``save_dir`` plus its own name; removing the folder would orphan it."""
    folder = await a_folder()
    await a_collection(folder=folder)

    with pytest.raises(IntegrityError):
        await folder.delete()


@pytest.mark.asyncio
async def test_a_collection_has_no_stored_path_and_no_reverse_accessor(sqlite: None) -> None:
    assert "path" not in Collection._meta.fields_map
    assert not hasattr(await a_collection(), "downloads")
