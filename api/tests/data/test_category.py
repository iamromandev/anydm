"""Categories: the seeded rows, the slug rule, and the columns a download gains."""

import uuid

import pytest
from src.data.db.model import Category, Download
from src.data.type import DOWNLOADS_ID, SEEDED_CATEGORIES, slugify


def test_downloads_is_first_seeded_and_has_a_fixed_id_and_the_root_folder() -> None:
    assert uuid.UUID("00000000-0000-4000-8000-000000000001") == DOWNLOADS_ID
    assert SEEDED_CATEGORIES[0] == ("Downloads", "downloads", "")


def test_one_category_per_old_folder_value_each_in_a_folder_named_by_its_slug() -> None:
    slugs = [slug for _, slug, _ in SEEDED_CATEGORIES]
    assert slugs == [
        "downloads", "videos", "movies", "tv_shows", "music", "audiobooks", "podcasts", "documents",
        "ebooks", "images", "photos", "software", "games", "archives", "other",
    ]
    assert all(folder == slug for _, slug, folder in SEEDED_CATEGORIES[1:])


@pytest.mark.parametrize(
    ("name", "slug"),
    [("TV shows", "tv_shows"), ("  Lectures  ", "lectures"), ("Edu / Lectures!", "edu_lectures"), ("Été", "été"), ("!!", "")],
)
def test_a_slug_is_the_name_lower_cased_with_one_underscore_between_words(name: str, slug: str) -> None:
    assert slugify(name) == slug


def test_slugify_collapses_existing_underscore_runs() -> None:
    assert slugify("a _ b") == "a_b"
    assert slugify("a__b") == "a_b"


def test_category_has_its_columns_and_lives_in_transfer() -> None:
    fields = Category._meta.fields_map
    assert {"name", "slug", "folder", "position", "builtin"} <= set(fields)
    assert fields["name"].unique and fields["slug"].unique
    assert Category._meta.db_table == "category" and Category._meta.schema == "transfer"


def test_a_download_has_a_category_and_a_stored_folder() -> None:
    fields = Download._meta.fields_map
    assert fields["category"].null is False
    assert fields["folder"].null is True
