"""A tag is a name and a colour; it says nothing about what it labels."""

import pytest
from src.data.db.model import Tag
from tortoise.exceptions import IntegrityError


@pytest.mark.asyncio
async def test_a_tag_needs_only_a_name(sqlite: None) -> None:
    tag = await Tag.create(name="favourites")

    assert tag.colour is None
    assert (await Tag.get(id=tag.id)).name == "favourites"


@pytest.mark.asyncio
async def test_a_tag_can_carry_a_colour(sqlite: None) -> None:
    tag = await Tag.create(name="urgent", colour="#d33")

    assert (await Tag.get(id=tag.id)).colour == "#d33"


@pytest.mark.asyncio
async def test_a_tag_name_is_unique(sqlite: None) -> None:
    await Tag.create(name="music")

    with pytest.raises(IntegrityError):
        await Tag.create(name="music")


@pytest.mark.asyncio
async def test_tags_list_by_name(sqlite: None) -> None:
    for name in ("zebra", "apple", "mango"):
        await Tag.create(name=name)

    assert [tag.name for tag in await Tag.all()] == ["apple", "mango", "zebra"]
