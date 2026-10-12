"""The rules a category files by: how they are written down, and what a category may be given."""

import pytest
from src.core.error import Error
from src.service.category.category_service import clean_extensions, clean_mime_prefixes


def test_extensions_are_lowercased_given_a_dot_and_deduplicated() -> None:
    assert clean_extensions(["MKV", ".mp4", " .MKV ", "tar.gz"]) == [".mkv", ".mp4", ".tar.gz"]
    assert clean_extensions([]) == []


@pytest.mark.parametrize("bad", ["", ".", "a b", ".a/b", "..mkv", "*.mkv"])
def test_a_bad_extension_is_refused(bad: str) -> None:
    with pytest.raises(Error) as raised:
        clean_extensions([bad])

    assert int(raised.value.code) == 422


def test_mime_prefixes_are_lowercased_and_a_wildcard_means_the_prefix() -> None:
    assert clean_mime_prefixes(["Video/*", "audio/", "application/pdf", "video/"]) == [
        "video/",
        "audio/",
        "application/pdf",
    ]


@pytest.mark.parametrize("bad", ["", "video", "/mp4", "vid eo/", "*"])
def test_a_bad_mime_prefix_is_refused(bad: str) -> None:
    with pytest.raises(Error) as raised:
        clean_mime_prefixes([bad])

    assert int(raised.value.code) == 422
