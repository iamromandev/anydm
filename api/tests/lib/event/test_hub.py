import asyncio

import pytest
from src.lib.event.hub import EventHub


@pytest.mark.asyncio
async def test_a_subscriber_receives_a_published_event() -> None:
    hub = EventHub()
    subscription = hub.subscribe()
    hub.publish("task", {"id": 1})

    event, data = await asyncio.wait_for(anext(aiter(subscription)), timeout=1)

    assert event == "task"
    assert data == {"id": 1}
    subscription.close()


@pytest.mark.asyncio
async def test_every_subscriber_receives_the_same_event() -> None:
    hub = EventHub()
    first, second = hub.subscribe(), hub.subscribe()
    hub.publish("stats", {"speed": 5})

    assert (await anext(aiter(first)))[1] == {"speed": 5}
    assert (await anext(aiter(second)))[1] == {"speed": 5}
    first.close()
    second.close()


@pytest.mark.asyncio
async def test_publishing_with_no_subscribers_is_harmless() -> None:
    EventHub().publish("task", {"id": 1})


@pytest.mark.asyncio
async def test_subscriber_count_tracks_open_subscriptions() -> None:
    hub = EventHub()
    assert hub.subscriber_count() == 0
    subscription = hub.subscribe()
    assert hub.subscriber_count() == 1
    subscription.close()
    assert hub.subscriber_count() == 0


@pytest.mark.asyncio
async def test_a_full_queue_drops_the_oldest_frame_instead_of_blocking() -> None:
    hub = EventHub(max_queue=2)
    subscription = hub.subscribe()

    for index in range(5):
        hub.publish("task", {"id": index})

    received = [(await anext(aiter(subscription)))[1] for _ in range(2)]

    # The two most recent survive; the earlier three were dropped.
    assert received == [{"id": 3}, {"id": 4}]
    subscription.close()


@pytest.mark.asyncio
async def test_a_closed_subscription_stops_iterating() -> None:
    hub = EventHub()
    subscription = hub.subscribe()
    subscription.close()

    with pytest.raises(StopAsyncIteration):
        await asyncio.wait_for(anext(aiter(subscription)), timeout=1)


@pytest.mark.asyncio
async def test_closing_unblocks_a_waiting_reader() -> None:
    hub = EventHub()
    subscription = hub.subscribe()
    iterator = aiter(subscription)

    async def read() -> tuple[str, object]:
        return await anext(iterator)

    waiter = asyncio.create_task(read())
    await asyncio.sleep(0)

    subscription.close()

    with pytest.raises(StopAsyncIteration):
        await asyncio.wait_for(waiter, timeout=1)


@pytest.mark.asyncio
async def test_publishing_to_a_closed_subscription_does_not_raise() -> None:
    hub = EventHub()
    subscription = hub.subscribe()
    subscription.close()
    hub.publish("task", {"id": 1})
    assert hub.subscriber_count() == 0
