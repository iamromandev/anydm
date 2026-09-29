"""The built-in sources' constants, and the check every address goes through."""

import pytest
from src.lib.sources.registry import BUILTINS, validate_base_url


def test_every_builtin_declares_its_constants() -> None:
    assert list(BUILTINS) == ["apibay", "nyaa", "eztv"]
    for builtin in BUILTINS.values():
        assert builtin.label and builtin.default_enabled is True
        assert validate_base_url(builtin.default_url) == builtin.default_url
        assert builtin.make(builtin.default_url).name == builtin.name
    assert BUILTINS["apibay"].label == "apibay"
    assert BUILTINS["nyaa"].label == "Nyaa"
    assert BUILTINS["eztv"].label == "EZTV"


def test_only_apibay_needs_a_query_to_test() -> None:
    assert BUILTINS["apibay"].test_query == "ubuntu"
    assert BUILTINS["nyaa"].test_query == ""
    assert BUILTINS["eztv"].test_query == ""


@pytest.mark.parametrize(
    ("text", "expected"),
    [
        ("https://apibay.org", "https://apibay.org"),
        ("  https://apibay.org/  ", "https://apibay.org"),
        ("http://192.168.1.10:8080/x//", "http://192.168.1.10:8080/x"),
    ],
)
def test_an_address_is_trimmed_and_loses_its_trailing_slashes(text: str, expected: str) -> None:
    assert validate_base_url(text) == expected


@pytest.mark.parametrize("text", ["", "   ", "apibay.org", "ftp://x.test", "https://", "https://a b.test", "http://x.test:notaport"])
def test_a_bad_address_is_refused_in_words(text: str) -> None:
    with pytest.raises(ValueError, match="address"):
        validate_base_url(text)
