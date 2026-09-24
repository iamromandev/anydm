import pytest
from src.data.type import ACTIVE_STATUSES, Kind, Platform, Preset, TaskStatus


def test_preset_values_match_the_bun_api() -> None:
    assert {p.value for p in Preset} == {"best", "2160", "1440", "1080", "720", "480", "mp3"}


@pytest.mark.parametrize(
    ("preset", "height"),
    [(Preset.BEST, None), (Preset.P2160, 2160), (Preset.P1080, 1080), (Preset.P480, 480), (Preset.MP3, None)],
)
def test_target_height(preset: Preset, height: int | None) -> None:
    assert preset.target_height == height


def test_terminal_statuses() -> None:
    assert TaskStatus.COMPLETE.is_terminal is True
    assert TaskStatus.FAILED.is_terminal is True
    assert TaskStatus.CANCELED.is_terminal is True
    assert TaskStatus.DOWNLOADING.is_terminal is False
    assert TaskStatus.PAUSED.is_terminal is False


def test_active_statuses_are_the_ones_a_restart_must_requeue() -> None:
    assert frozenset({TaskStatus.DOWNLOADING, TaskStatus.MUXING}) == ACTIVE_STATUSES


def test_platform_and_kind_values() -> None:
    assert {p.value for p in Platform} == {"site", "direct", "torrent"}
    assert {k.value for k in Kind} == {"video", "audio", "file", "torrent"}


def test_seeding_is_a_status_and_is_not_terminal() -> None:
    assert TaskStatus.SEEDING.value == "seeding"
    assert TaskStatus.SEEDING.is_terminal is False


def test_seeding_is_not_an_active_status() -> None:
    """A seeding torrent is not mid-transfer, so a restart must not requeue it."""
    assert TaskStatus.SEEDING not in ACTIVE_STATUSES
