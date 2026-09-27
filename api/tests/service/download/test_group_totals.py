"""A group's totals follow its videos (v0.5)."""

import uuid
from typing import Any

import pytest
from src.data.type import Kind, Platform, Preset
from src.data.type import TaskStatus as S
from src.lib.event import EventHub
from src.service.download.group_totals import GroupTotals, counts_of, group_status

ROWS = [(S.COMPLETE, 10, 10, 0), (S.DOWNLOADING, 5, 20, 3), (S.PENDING, 0, None, 0), (S.FAILED, 0, None, 0)]


@pytest.mark.parametrize(
    ("statuses", "expected"),
    [
        ([S.PENDING, S.COMPLETE], S.DOWNLOADING),
        ([S.DOWNLOADING, S.PAUSED], S.DOWNLOADING),
        ([S.MUXING], S.DOWNLOADING),
        ([S.PAUSED, S.COMPLETE, S.FAILED], S.PAUSED),
        ([S.FAILED, S.COMPLETE], S.FAILED),
        ([S.COMPLETE, S.COMPLETE], S.COMPLETE),
    ],
)
def test_a_group_status_follows_its_videos(statuses: list[S], expected: S) -> None:
    assert group_status(statuses) == expected


def test_counts_sum_by_kind_of_status() -> None:
    counts = counts_of(ROWS)

    assert (counts.total, counts.complete, counts.active, counts.failed, counts.paused) == (4, 1, 2, 1, 0)


class _Group:
    def __init__(self) -> None:
        self.id = uuid.uuid4()
        self.source_url = "https://y.test/list"
        self.platform = Platform.SITE
        self.preset = Preset.BEST
        self.kind = Kind.PLAYLIST
        self.status = S.PENDING
        self.progress = 0
        self.downloaded_bytes = 0
        self.total_bytes: int | None = None
        self.speed_bps = 0
        self.saved: list[str] = []

    async def save(self, update_fields: list[str]) -> None:
        self.saved = list(update_fields)


class _Repo:
    def __init__(self, group: _Group) -> None:
        self.group = group

    async def get_active_by_id(self, task_id: uuid.UUID) -> Any:
        return self.group if task_id == self.group.id else None

    async def entry_statuses(self, group_id: uuid.UUID) -> list[Any]:
        return list(ROWS)


@pytest.mark.asyncio
async def test_refresh_writes_the_totals_and_publishes_one_frame() -> None:
    group = _Group()
    hub = EventHub()
    subscription = hub.subscribe()

    schema = await GroupTotals(repo=_Repo(group), hub=hub).refresh(group.id)  # ty: ignore[invalid-argument-type]

    assert (group.status, group.progress, group.downloaded_bytes, group.total_bytes, group.speed_bps) == (
        S.DOWNLOADING,
        25,
        15,
        30,
        3,
    )
    assert "status" in group.saved
    assert schema is not None and schema.entry_counts is not None and schema.entry_counts.total == 4
    event, data = await anext(aiter(subscription))
    assert event == "task" and data["entry_counts"]["total"] == 4
    subscription.close()


@pytest.mark.asyncio
async def test_a_removed_group_is_left_alone() -> None:
    group = _Group()

    assert await GroupTotals(repo=_Repo(group), hub=EventHub()).refresh(uuid.uuid4()) is None  # ty: ignore[invalid-argument-type]
