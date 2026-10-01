"""One copy per search result, and the source it came from."""

import pytest
from pydantic import ValidationError
from src.data.schema.search import SearchResultSchema


def _result(**fields: object) -> SearchResultSchema:
    base: dict[str, object] = {"title": "Bunny", "indexers": ["p"], "copy_from": "p"}
    return SearchResultSchema(**{**base, **fields})  # type: ignore[arg-type]


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
    with pytest.raises(ValidationError):
        SearchResultSchema(title="Bunny", indexers=["p"], magnet="magnet:?xt=urn:btih:aa")
