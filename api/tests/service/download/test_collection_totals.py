import uuid
from types import SimpleNamespace
from typing import Any

import pytest
from src.data.type import DownloadStatus as S
from src.data.type import MediaKind, Preset
from src.lib.event import EventHub
from src.service.download.collection_totals import CollectionTotals, collection_status, counts_of
from src.service.download.live import Live, LiveStats


def test_status_order() -> None:
    assert collection_status([S.COMPLETE, S.PENDING]) == S.DOWNLOADING
    assert collection_status([S.COMPLETE, S.PAUSED, S.FAILED]) == S.PAUSED
    assert collection_status([S.COMPLETE, S.FAILED]) == S.FAILED
    assert collection_status([]) == S.COMPLETE


def test_counts() -> None:
    ids = [uuid.uuid4() for _ in range(4)]
    rows = [
        (ids[0], S.COMPLETE, 9, 9),
        (ids[1], S.DOWNLOADING, 1, 9),
        (ids[2], S.PENDING, 0, None),
        (ids[3], S.FAILED, 0, 2),
    ]
    counts = counts_of(rows)
    assert (counts.total, counts.complete, counts.active, counts.downloading, counts.failed) == (4, 1, 2, 1, 1)


class Repo:
    def __init__(self, collection: Any, rows: list[Any]) -> None:
        self.collection, self.rows = collection, rows

    async def get_active_by_id(self, collection_id: uuid.UUID) -> Any:
        return self.collection

    async def member_rows(self, collection_id: uuid.UUID) -> list[Any]:
        return self.rows

    async def watched_counts(self, ids: list[uuid.UUID]) -> dict[uuid.UUID, int]:
        return dict.fromkeys(ids, 1)


@pytest.mark.asyncio
async def test_refresh_publishes_a_collection_frame_with_live_speed() -> None:
    member = uuid.uuid4()
    collection = SimpleNamespace(
        id=uuid.uuid4(),
        media_kind=MediaKind.PLAYLIST,
        provider="Youtube",
        ref_id="PL",
        title="Talks",
        path="Talks [PL]",
        site_detail=SimpleNamespace(preset=Preset.BEST),
        created_at=None,
    )
    hub, live = EventHub(), LiveStats()
    live.set(member, Live(speed_bps=40))
    subscription = hub.subscribe()
    totals = CollectionTotals(Repo(collection, [(member, S.DOWNLOADING, 5, 10)]), hub, live)  # ty: ignore[invalid-argument-type]
    schema = await totals.refresh(collection.id)
    assert schema is not None
    assert (schema.speed_bps, schema.status, schema.total_size) == (40, S.DOWNLOADING, 10)
    event, data = await anext(aiter(subscription))
    assert event == "collection"
    assert data["id"] == str(collection.id)
    subscription.close()


@pytest.mark.asyncio
async def test_schemas_carry_the_watched_count() -> None:
    collection = SimpleNamespace(
        id=uuid.uuid4(),
        media_kind=MediaKind.CHANNEL,
        provider="Youtube",
        ref_id="UC",
        title="",
        path="UC",
        site_detail=SimpleNamespace(preset=Preset.BEST),
        created_at=None,
    )
    totals = CollectionTotals(Repo(collection, []), EventHub(), LiveStats())  # ty: ignore[invalid-argument-type]
    (schema,) = await totals.schemas([collection])
    assert schema.counts.watched == 1
    assert schema.status == S.COMPLETE
