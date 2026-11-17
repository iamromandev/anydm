import pytest
from src.core.errors import DownloadError
from src.plugin.contract import (
    MAX_FIELD,
    MAX_LINE,
    MAX_LINES,
    PROTOCOL,
    ContractError,
    LineReader,
    clean_text,
    parse_info,
    parse_manifest,
)

GOOD = {"type": "manifest", "name": "example", "version": "1.0", "capabilities": ["check", "download"],
        "permissions": ["network"], "description": "An example"}


def test_the_protocol_is_3() -> None:
    assert PROTOCOL == 3


def test_a_contract_error_is_a_download_error() -> None:
    assert issubclass(ContractError, DownloadError)


def test_a_line_is_parsed_into_a_dict() -> None:
    assert LineReader().feed(b'{"type": "notice", "message": "hi"}\n') == {"type": "notice", "message": "hi"}


@pytest.mark.parametrize("raw", [b"[1, 2]\n", b'"x"\n', b'{"type": "mystery"}\n', b'{"no": "type"}\n', b'{"type": 3}\n'])
def test_other_shapes_and_unknown_types_are_ignored(raw: bytes) -> None:
    assert LineReader().feed(raw) is None


def test_a_line_over_the_limit_raises() -> None:
    with pytest.raises(ContractError, match="too long"):
        LineReader().feed(b"x" * (MAX_LINE + 1))


def test_bad_json_raises() -> None:
    with pytest.raises(ContractError, match="not JSON"):
        LineReader().feed(b"{broken\n")


def test_too_many_lines_raise() -> None:
    reader = LineReader()
    for _ in range(MAX_LINES):
        reader.feed(b'{"type": "notice", "message": "x"}\n')
    with pytest.raises(ContractError, match="too many lines"):
        reader.feed(b'{"type": "notice", "message": "x"}\n')


def test_a_dropped_line_still_counts() -> None:
    reader = LineReader()
    for _ in range(MAX_LINES):
        reader.drop()
    with pytest.raises(ContractError, match="too many lines"):
        reader.drop()


def test_clean_text_removes_controls_and_bidi_and_caps_the_length() -> None:
    assert clean_text("a\x1b[31mb‮c\n d") == "a[31mbc d"
    assert len(clean_text("x" * 5000)) == MAX_FIELD


@pytest.mark.parametrize("value", [None, 3, ["a"], {"a": 1}, b"x"])
def test_clean_text_of_a_non_string_is_empty(value: object) -> None:
    assert clean_text(value) == ""


def test_a_manifest_is_parsed() -> None:
    m = parse_manifest(GOOD)
    assert (m.name, m.version, m.description) == ("example", "1.0", "An example")
    assert m.capabilities == {"check", "download"} and m.permissions == {"network"}


@pytest.mark.parametrize("name", ["media", "http", "torrent", "A", "x", "1ab", "has space", "a" * 33, "-ab", 5, None])
def test_a_bad_or_reserved_name_is_refused(name: object) -> None:
    with pytest.raises(ContractError, match="name"):
        parse_manifest({**GOOD, "name": name})


@pytest.mark.parametrize("key,value", [("capabilities", ["fly"]), ("capabilities", "check"), ("capabilities", []),
                                       ("permissions", ["root"]), ("permissions", "network")])
def test_unknown_or_malformed_capabilities_and_permissions_are_refused(key: str, value: object) -> None:
    with pytest.raises(ContractError):
        parse_manifest({**GOOD, key: value})


def test_a_manifest_without_permissions_asks_for_none() -> None:
    assert parse_manifest({k: v for k, v in GOOD.items() if k != "permissions"}).permissions == frozenset()


def test_info_is_parsed_and_cleaned() -> None:
    info = parse_info({"type": "info", "title": "T\x1b", "size": 12,
                       "formats": [{"id": "1", "ext": "mp4", "note": "720p"}, "junk", {"id": 3}],
                       "items": [{"title": "a", "url": "https://x/a"}, {"title": 1}, 7]})
    assert info.title == "T" and info.size == 12
    assert [(f.id, f.ext, f.note) for f in info.formats] == [("1", "mp4", "720p")]
    assert [(i.title, i.url) for i in info.items] == [("a", "https://x/a")]


@pytest.mark.parametrize("size", [-1, "12", True, 1.5, None])
def test_a_bad_size_becomes_none(size: object) -> None:
    assert parse_info({"type": "info", "size": size}).size is None


def test_info_lists_are_capped() -> None:
    info = parse_info({"type": "info", "formats": [{"id": str(i)} for i in range(5000)],
                       "items": [{"title": "t", "url": "u"}] * 5000})
    assert len(info.formats) <= 500 and len(info.items) <= 500


def test_progress_lines_are_not_counted_but_bad_json_is(monkeypatch: pytest.MonkeyPatch) -> None:
    from src.plugin.contract import BadJson, LineReader

    monkeypatch.setattr("src.plugin.contract.MAX_LINES", 3)
    reader = LineReader()
    for _ in range(50):
        assert reader.feed(b'{"type": "progress"}') is not None
    with pytest.raises(ContractError, match="too many lines"):
        for _ in range(10):
            with pytest.raises(BadJson):
                reader.feed(b"junk")
