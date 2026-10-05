"""A queue is a named lane downloads wait in. Which one is the default is a flag, not a name."""

import pytest
from src.data.db.model import Queue
from tortoise.exceptions import IntegrityError


@pytest.mark.asyncio
async def test_a_queue_needs_a_name_and_a_slug_and_is_open_and_not_the_default(sqlite: None) -> None:
    queue = await Queue.create(name="Night", slug="night")

    found = await Queue.get(id=queue.id)
    assert (found.name, found.slug) == ("Night", "night")
    assert (found.is_default, found.is_paused) == (False, False)
    assert found.max_concurrent == 1
    assert (found.start_time, found.stop_time, found.days) == (None, None, None)


@pytest.mark.asyncio
async def test_a_queue_name_is_unique(sqlite: None) -> None:
    await Queue.create(name="Night", slug="night")

    with pytest.raises(IntegrityError):
        await Queue.create(name="Night", slug="night-2")


@pytest.mark.asyncio
async def test_a_queue_slug_is_unique(sqlite: None) -> None:
    await Queue.create(name="Night", slug="night")

    with pytest.raises(IntegrityError):
        await Queue.create(name="Nights", slug="night")


@pytest.mark.asyncio
async def test_the_default_queue_is_found_by_its_flag_not_its_name(sqlite: None) -> None:
    await Queue.create(name="Night", slug="night")
    await Queue.create(name="Everything", slug="everything", is_default=True)

    assert (await Queue.get(is_default=True)).slug == "everything"


@pytest.mark.asyncio
async def test_a_paused_queue_is_a_flag_on_the_queue(sqlite: None) -> None:
    queue = await Queue.create(name="Night", slug="night", is_paused=True)

    assert (await Queue.get(id=queue.id)).is_paused is True
