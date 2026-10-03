"""A tag is a label on one row, named by that row's kind and id."""

import uuid

import pytest
from src.data.db.model import Tag
from src.data.type import RefType
from tortoise.exceptions import IntegrityError


@pytest.mark.asyncio
async def test_a_tag_labels_one_row(sqlite: None) -> None:
    row = uuid.uuid4()

    tag = await Tag.create(name="Favourites", slug="favourites", ref_type=RefType.DOWNLOAD, ref_id=row)

    found = await Tag.get(id=tag.id)
    assert (found.name, found.slug, found.ref_type, found.ref_id) == ("Favourites", "favourites", "download", row)


@pytest.mark.asyncio
async def test_the_row_need_not_exist(sqlite: None) -> None:
    """No foreign key: ``ref_id`` names a row of any table, so the repository checks the target."""
    await Tag.create(name="Music", slug="music", ref_type=RefType.COLLECTION, ref_id=uuid.uuid4())

    assert await Tag.all().count() == 1


@pytest.mark.asyncio
async def test_a_row_carries_a_slug_once(sqlite: None) -> None:
    row = uuid.uuid4()
    await Tag.create(name="Music", slug="music", ref_type=RefType.DOWNLOAD, ref_id=row)

    with pytest.raises(IntegrityError):
        await Tag.create(name="MUSIC", slug="music", ref_type=RefType.DOWNLOAD, ref_id=row)


@pytest.mark.asyncio
async def test_another_row_may_use_the_same_slug(sqlite: None) -> None:
    await Tag.create(name="Music", slug="music", ref_type=RefType.DOWNLOAD, ref_id=uuid.uuid4())
    await Tag.create(name="Music", slug="music", ref_type=RefType.DOWNLOAD, ref_id=uuid.uuid4())

    assert await Tag.filter(slug="music").count() == 2


@pytest.mark.asyncio
async def test_the_same_id_of_another_kind_is_another_row(sqlite: None) -> None:
    row = uuid.uuid4()

    await Tag.create(name="Music", slug="music", ref_type=RefType.DOWNLOAD, ref_id=row)
    await Tag.create(name="Music", slug="music", ref_type=RefType.COLLECTION, ref_id=row)

    assert await Tag.filter(ref_id=row).count() == 2


@pytest.mark.asyncio
async def test_everything_with_a_tag_is_found_by_its_slug_and_kind(sqlite: None) -> None:
    mine, other = uuid.uuid4(), uuid.uuid4()
    await Tag.create(name="Music", slug="music", ref_type=RefType.DOWNLOAD, ref_id=mine)
    await Tag.create(name="Music", slug="music", ref_type=RefType.COLLECTION, ref_id=other)
    await Tag.create(name="Film", slug="film", ref_type=RefType.DOWNLOAD, ref_id=other)

    ids = await Tag.filter(slug="music", ref_type=RefType.DOWNLOAD).values_list("ref_id", flat=True)

    assert ids == [mine]


@pytest.mark.asyncio
async def test_one_row_lists_its_tags_by_name(sqlite: None) -> None:
    row = uuid.uuid4()
    for name in ("Zebra", "Apple", "Mango"):
        await Tag.create(name=name, slug=name.lower(), ref_type=RefType.DOWNLOAD, ref_id=row)

    tags = await Tag.filter(ref_type=RefType.DOWNLOAD, ref_id=row)

    assert [tag.name for tag in tags] == ["Apple", "Mango", "Zebra"]
