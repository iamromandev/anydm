import asyncio
import uuid

import pytest
from src.service.download.control import DownloadControl


def test_stop_registry_roundtrip() -> None:
    control = DownloadControl()
    task_id = uuid.uuid4()

    assert control.is_stopping(task_id) is False
    control.request_stop(task_id)
    assert control.is_stopping(task_id) is True
    control.clear_stop(task_id)
    assert control.is_stopping(task_id) is False


def test_stops_are_independent() -> None:
    control = DownloadControl()
    first, second = uuid.uuid4(), uuid.uuid4()
    control.request_stop(first)
    assert control.is_stopping(second) is False


@pytest.mark.asyncio
async def test_wait_for_work_returns_immediately_once_woken() -> None:
    control = DownloadControl()
    control.wake()
    await asyncio.wait_for(control.wait_for_work(timeout=5), timeout=1)


@pytest.mark.asyncio
async def test_wait_for_work_times_out_when_idle() -> None:
    control = DownloadControl()
    await asyncio.wait_for(control.wait_for_work(timeout=0.05), timeout=1)


@pytest.mark.asyncio
async def test_the_wake_flag_is_consumed() -> None:
    control = DownloadControl()
    control.wake()
    await control.wait_for_work(timeout=5)
    # Second call must block until the timeout rather than returning at once.
    loop = asyncio.get_running_loop()
    started = loop.time()
    await control.wait_for_work(timeout=0.1)
    assert loop.time() - started >= 0.05
