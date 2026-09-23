import errno
from collections import namedtuple

import pytest
from src.core.error import Error
from src.core.type import Code, ErrorType
from src.service.download.disk import DiskGuard, is_insufficient_storage, storage_error

GIB = 1024**3
_Usage = namedtuple("_Usage", ["total", "used", "free"])


def _guard(free: int, *, min_free: int = GIB, total: int = 100 * GIB) -> DiskGuard:
    return DiskGuard("/data", min_free, usage=lambda _path: _Usage(total, total - free, free))


def test_usage_reports_the_download_dir_and_the_minimum() -> None:
    usage = _guard(free=40 * GIB).usage()

    assert usage is not None
    assert (usage.path, usage.total_bytes, usage.free_bytes, usage.min_free_bytes) == (
        "/data",
        100 * GIB,
        40 * GIB,
        GIB,
    )


def test_require_passes_while_free_space_stays_above_the_minimum() -> None:
    _guard(free=2 * GIB).require()


def test_require_refuses_below_the_minimum_with_507() -> None:
    with pytest.raises(Error) as caught:
        _guard(free=GIB // 2).require()

    assert caught.value.code == Code.INSUFFICIENT_STORAGE
    assert caught.value.type == ErrorType.INSUFFICIENT_STORAGE
    assert caught.value.retry_able is True
    assert caught.value.message == "Not enough disk space: 0.5 GB free, 1.0 GB must stay free"


def test_require_counts_the_size_of_what_is_about_to_be_written() -> None:
    # 3 GiB free covers the 1 GiB minimum, but not the minimum plus a 2.5 GiB file.
    guard = _guard(free=3 * GIB)

    guard.require(extra_bytes=GIB)
    with pytest.raises(Error) as caught:
        guard.require(extra_bytes=5 * GIB // 2)

    assert caught.value.message == (
        "Not enough disk space: 3.0 GB free, 2.5 GB to write and 1.0 GB must stay free"
    )


def test_a_minimum_of_zero_turns_the_guard_off() -> None:
    _guard(free=0, min_free=0).require(extra_bytes=10 * GIB)


def test_a_disk_that_cannot_be_read_does_not_block_downloads() -> None:
    def unreadable(_path: str) -> _Usage:
        raise FileNotFoundError("/data")

    guard = DiskGuard("/data", GIB, usage=unreadable)

    assert guard.usage() is None
    guard.require(extra_bytes=GIB)


def test_enospc_becomes_the_same_error_as_the_guard() -> None:
    error = storage_error(OSError(errno.ENOSPC, "No space left on device"))

    assert error is not None
    assert is_insufficient_storage(error)
    assert error.code == Code.INSUFFICIENT_STORAGE


def test_other_os_errors_are_not_storage_errors() -> None:
    assert storage_error(OSError(errno.EACCES, "Permission denied")) is None


def test_only_insufficient_storage_counts_as_insufficient_storage() -> None:
    assert not is_insufficient_storage(Error.conflict(message="no"))
