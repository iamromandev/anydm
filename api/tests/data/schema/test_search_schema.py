"""One copy per search result, and the source it came from."""

from typing import Any

import pytest
from pydantic import ValidationError
from src.data.schema.search import SearchResultSchema


def _result(**fields: Any) -> SearchResultSchema:
    base: dict[str, Any] = {"title": "Bunny", "indexers": ["p"], "copy_from": "p"}
    return SearchResultSchema(**{**base, **fields})


def test_a_result_carries_its_source_and_one_copy() -> None:
    result = _result(magnet="magnet:?xt=urn:btih:aa")

    assert (result.copy_from, result.magnet, result.link) == ("p", "magnet:?xt=urn:btih:aa", None)


def test_a_link_result_carries_no_magnet() -> None:
    assert _result(link="http://p/dl/1").magnet is None


def test_a_result_with_both_copies_is_refused() -> None:
    with pytest.raises(ValidationError):
        _result(magnet="magnet:?xt=urn:btih:aa", link="http://p/dl/1")


def test_a_result_with_no_copy_is_refused() -> None:
    with pytest.raises(ValidationError):
        _result()


def test_a_result_without_its_source_is_refused() -> None:
    """Untrusted data, so it arrives as a dict: a source may leave the field out."""
    with pytest.raises(ValidationError):
        SearchResultSchema.model_validate({"title": "Bunny", "indexers": ["p"], "magnet": "magnet:?xt=urn:btih:aa"})
