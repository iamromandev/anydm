"""Bencoding (BEP 3). Strict: rejects leading zeros, truncated and trailing data, and absurd nesting."""

from typing import Any

type Value = int | bytes | list[Any] | dict[bytes, Any]

MAX_DEPTH = 64
MAX_DIGITS = 20  # in an integer or a string length: a u64 fits, and int() never meets a run long enough to raise


class BencodeError(ValueError):
    pass


def encode(value: object) -> bytes:
    if isinstance(value, bool):
        raise BencodeError("bool is not bencodable")
    if isinstance(value, int):
        return b"i%de" % value
    if isinstance(value, bytes):
        return b"%d:%s" % (len(value), value)
    if isinstance(value, list):
        return b"l" + b"".join(encode(v) for v in value) + b"e"
    if isinstance(value, dict):
        return b"d" + b"".join(encode(k) + encode(v) for k, v in sorted(value.items())) + b"e"
    raise BencodeError(f"cannot encode {type(value).__name__}")


class _Reader:
    def __init__(self, data: bytes, max_depth: int = MAX_DEPTH) -> None:
        self.data = data
        self.pos = 0
        self.max_depth = max_depth

    def value(self, depth: int = 0) -> Value:
        if depth > self.max_depth:
            raise BencodeError("nested too deeply")
        if self.pos >= len(self.data):
            raise BencodeError("unexpected end of data")
        c = self.data[self.pos : self.pos + 1]
        if c == b"i":
            return self._int()
        if c == b"l":
            self.pos += 1
            items: list[Value] = []
            while self._peek() != b"e":
                items.append(self.value(depth + 1))
            self.pos += 1
            return items
        if c == b"d":
            self.pos += 1
            out: dict[bytes, Value] = {}
            while self._peek() != b"e":
                key = self.value(depth + 1)
                if not isinstance(key, bytes):
                    raise BencodeError("dictionary key is not a string")
                out[key] = self.value(depth + 1)
            self.pos += 1
            return out
        if c.isdigit():
            return self._str()
        raise BencodeError(f"unexpected byte at {self.pos}")

    def _peek(self) -> bytes:
        if self.pos >= len(self.data):
            raise BencodeError("unexpected end of data")
        return self.data[self.pos : self.pos + 1]

    def _int(self) -> int:
        end = self.data.find(b"e", self.pos)
        if end < 0:
            raise BencodeError("unterminated integer")
        text = self.data[self.pos + 1 : end]
        digits = text[1:] if text.startswith(b"-") else text
        if not digits.isdigit() or (digits.startswith(b"0") and len(digits) > 1) or text == b"-0":
            raise BencodeError("malformed integer")
        if len(digits) > MAX_DIGITS:
            raise BencodeError("integer is too long")
        self.pos = end + 1
        return int(text)

    def _str(self) -> bytes:
        colon = self.data.find(b":", self.pos)
        head = self.data[self.pos : colon] if colon >= 0 else b""
        if not head.isdigit() or (head.startswith(b"0") and len(head) > 1):
            raise BencodeError("malformed string length")
        if len(head) > MAX_DIGITS:
            raise BencodeError("string length is too long")
        start, n = colon + 1, int(head)
        if start + n > len(self.data):
            raise BencodeError("string runs past the end of the data")
        self.pos = start + n
        return self.data[start : start + n]


def decode_prefix(data: bytes, max_depth: int = MAX_DEPTH) -> tuple[Value, int]:
    """Decode the value at the start of `data` and say where it ended (BEP 9 puts raw bytes after a dictionary)."""
    r = _Reader(data, max_depth)
    value = r.value()
    return value, r.pos


def decode(data: bytes, max_depth: int = MAX_DEPTH) -> Value:
    r = _Reader(data, max_depth)
    value = r.value()
    if r.pos != len(data):
        raise BencodeError("trailing data")
    return value


def decode_top(data: bytes) -> tuple[dict[bytes, Value], dict[bytes, tuple[int, int]]]:
    """Decode a top-level dictionary, and also give the (start, end) byte span of each of its values, so a
    caller can hash the original bytes of `info` rather than a re-encoding."""
    r = _Reader(data)
    if r._peek() != b"d":
        raise BencodeError("not a dictionary")
    r.pos += 1
    out: dict[bytes, Value] = {}
    spans: dict[bytes, tuple[int, int]] = {}
    while r._peek() != b"e":
        key = r.value(1)
        if not isinstance(key, bytes):
            raise BencodeError("dictionary key is not a string")
        start = r.pos
        out[key] = r.value(1)
        spans[key] = (start, r.pos)
    r.pos += 1
    if r.pos != len(data):
        raise BencodeError("trailing data")
    return out, spans
