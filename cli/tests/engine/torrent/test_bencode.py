import pytest
from src.engine.torrent.bencode import BencodeError, decode, decode_top, encode


def test_round_trip() -> None:
    value = {b"a": [1, -2, b"xyz"], b"b": {b"c": b""}}
    assert decode(encode(value)) == value


def test_dict_keys_are_sorted_on_encode() -> None:
    assert encode({b"b": 1, b"a": 2}) == b"d1:ai2e1:bi1ee"


@pytest.mark.parametrize("raw", [b"", b"i1", b"ie", b"i-0e", b"i01e", b"5:ab", b"x", b"d1:a", b"li1e", b"i1ee"])
def test_malformed_input_is_rejected(raw: bytes) -> None:
    with pytest.raises(BencodeError):
        decode(raw)


def test_deeply_nested_input_is_rejected() -> None:
    with pytest.raises(BencodeError):
        decode(b"l" * 5000 + b"e" * 5000)


def test_decode_top_gives_raw_span_of_values() -> None:
    raw = b"d4:infod1:ai1ee3:zzzi5ee"
    value, spans = decode_top(raw)
    assert value[b"zzz"] == 5
    start, end = spans[b"info"]
    assert raw[start:end] == b"d1:ai1ee"


@pytest.mark.parametrize(
    "raw",
    [
        b"i" + b"9" * 5000 + b"e",
        b"i-" + b"9" * 5000 + b"e",
        b"9" * 5000 + b":x",
        b"d" + b"9" * 5000 + b":xi1ee",
        b"i" + b"1" * 21 + b"e",
        b"1" * 21 + b":x",
    ],
    ids=["huge-int", "huge-negative-int", "huge-length", "huge-key-length", "21-digit-int", "21-digit-length"],
)
def test_overlong_digit_runs_are_a_bencode_error_not_a_value_error(raw: bytes) -> None:
    with pytest.raises(BencodeError):
        decode(raw)


def test_large_but_sane_integers_still_decode() -> None:
    assert decode(b"i" + b"9" * 19 + b"e") == 10**19 - 1
    assert decode(b"i-" + b"9" * 19 + b"e") == -(10**19 - 1)


def test_decode_prefix_returns_the_value_and_where_it_ended() -> None:
    from src.engine.torrent.bencode import decode_prefix

    raw = encode({b"msg_type": 1, b"piece": 0})
    value, end = decode_prefix(raw + b"binary data")
    assert value == {b"msg_type": 1, b"piece": 0}
    assert end == len(raw)


def test_decode_prefix_rejects_a_truncated_value() -> None:
    from src.engine.torrent.bencode import decode_prefix

    with pytest.raises(BencodeError):
        decode_prefix(b"d3:abc")


def test_a_lower_depth_cap_can_be_asked_for() -> None:
    deep = b"l" * 10 + b"e" * 10
    assert decode(deep, max_depth=20) == [[[[[[[[[[]]]]]]]]]]
    with pytest.raises(BencodeError, match="deeply"):
        decode(deep, max_depth=5)
