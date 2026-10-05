"""Every download names what it is: relations to its provider and address rows."""

from typing import Any

import pytest
from src.data.db import model
from src.data.db.model import Download, Queue, SiteDetail
from src.data.type import DownloadStatus, Preset


async def a_download(**fields: Any) -> Download:
    queue, _ = await Queue.get_or_create(slug="main", defaults={"name": "Main", "is_default": True})
    base: dict[str, Any] = {
        "status": DownloadStatus.PENDING,
        "queue": queue,
    }
    base.update(fields)
    return await Download.create(**base)


@pytest.mark.asyncio
async def test_a_site_detail_keeps_only_what_the_site_alone_has(sqlite: None) -> None:
    row = await a_download()

    detail = await SiteDetail.create(download=row, preset=Preset.P1080, video_format="137", audio_format="140")

    assert (detail.preset, detail.video_format, detail.audio_format) == (Preset.P1080, "137", "140")
    assert "extractor" not in SiteDetail._meta.fields_map
    assert "video_id" not in SiteDetail._meta.fields_map


def test_a_torrent_has_no_detail_table() -> None:
    assert not hasattr(model, "TorrentDetail")
