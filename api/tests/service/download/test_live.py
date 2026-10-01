import uuid

from src.service.download.live import Live, LiveStats


def test_an_unknown_download_reads_as_still() -> None:
    assert LiveStats().get(uuid.uuid4()) == Live()


def test_set_get_clear() -> None:
    live = LiveStats()
    one = uuid.uuid4()
    live.set(one, Live(speed_bps=500, eta_seconds=9, upload_speed_bps=3, peers=4))
    assert live.get(one) == Live(speed_bps=500, eta_seconds=9, upload_speed_bps=3, peers=4)
    live.clear(one)
    assert live.get(one) == Live()


def test_speeds_lists_only_what_moves() -> None:
    live = LiveStats()
    fast, still = uuid.uuid4(), uuid.uuid4()
    live.set(fast, Live(speed_bps=10))
    live.set(still, Live(speed_bps=0, peers=2))
    assert live.speeds() == {fast: 10}
