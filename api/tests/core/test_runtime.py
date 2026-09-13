import pytest
from src.core.runtime import format_uptime, get_listen_addr, set_listen_addr


@pytest.mark.parametrize(
    ("seconds", "expected"),
    [
        (0, "0s"),
        (5, "5s"),
        (65, "1m 5s"),
        (3600, "1h"),
        (3665, "1h 1m 5s"),
        (90061, "1d 1h 1m 1s"),
    ],
)
def test_format_uptime(seconds: float, expected: str) -> None:
    assert format_uptime(seconds) == expected


def test_listen_addr_roundtrip() -> None:
    set_listen_addr("0.0.0.0", 8000)
    assert get_listen_addr() == ("0.0.0.0", 8000)
