"""The wire contract between anydm and a plugin container: JSON lines on stdout, versioned by PROTOCOL.

Everything a plugin prints is untrusted: this module bounds it (line length, line count, field length) and cleans
the text that reaches the screen."""

import json
import re
from collections.abc import Mapping
from dataclasses import dataclass

from src.core.errors import DownloadError
from src.core.names import clean_display

PROTOCOL = 3
MAX_LINE = 1_000_000  # bytes in one line
MAX_LINES = 10_000  # lines in one run
MAX_FIELD = 500  # characters of any text shown to the user
MAX_LIST = 500  # formats or items of one `info` line
CAPABILITIES = frozenset({"check", "download", "info"})
PERMISSIONS = frozenset({"network", "cookies"})
NAME_RE = re.compile(r"[a-z][a-z0-9-]{1,31}")
RESERVED = frozenset({"http", "torrent", "media"})
LINE_TYPES = frozenset({"hello", "manifest", "supported", "progress", "notice", "info", "done", "error"})


class ContractError(DownloadError):
    """A plugin broke the contract."""


class LineTooLong(ContractError):
    """One line was longer than MAX_LINE."""


class BadJson(ContractError):
    """One line was not JSON."""


def clean_text(v: object) -> str:
    """Untrusted text for the screen: no control or bidi characters, at most MAX_FIELD characters. Non-text is ''."""
    return clean_display(v)[:MAX_FIELD] if isinstance(v, str) else ""


@dataclass(frozen=True)
class Manifest:
    name: str
    version: str
    capabilities: frozenset[str]
    permissions: frozenset[str]
    description: str


def _names(raw: object, allowed: frozenset[str], what: str) -> frozenset[str]:
    if not isinstance(raw, list) or not all(isinstance(x, str) for x in raw):
        raise ContractError(f"the manifest's {what} must be a list of strings")
    unknown = sorted({clean_text(x) for x in raw if x not in allowed})
    if unknown:
        raise ContractError(f"the manifest asks for unknown {what}: {', '.join(unknown)}")
    return frozenset(raw)


def parse_manifest(obj: Mapping[str, object]) -> Manifest:
    name = obj.get("name")
    if not isinstance(name, str) or not NAME_RE.fullmatch(name):
        raise ContractError("the manifest's name must be 2 to 32 characters: a lowercase letter, then letters, digits, '-'")
    if name in RESERVED:
        raise ContractError(f"the manifest's name {name!r} is reserved")
    capabilities = _names(obj.get("capabilities"), CAPABILITIES, "capabilities")
    if not capabilities:
        raise ContractError("the manifest's capabilities are empty")
    permissions = _names(obj.get("permissions", []), PERMISSIONS, "permissions")
    return Manifest(name, clean_text(obj.get("version")), capabilities, permissions, clean_text(obj.get("description")))


@dataclass(frozen=True)
class Format:
    id: str
    ext: str
    note: str


@dataclass(frozen=True)
class Item:
    title: str
    url: str


@dataclass(frozen=True)
class Info:
    title: str | None
    size: int | None
    formats: list[Format]
    items: list[Item]


def _rows(raw: object) -> list[dict[str, object]]:
    return [r for r in raw[:MAX_LIST] if isinstance(r, dict)] if isinstance(raw, list) else []


def parse_info(obj: Mapping[str, object]) -> Info:
    size = obj.get("size")
    if not isinstance(size, int) or isinstance(size, bool) or size < 0:
        size = None
    formats = [Format(clean_text(r.get("id")), clean_text(r.get("ext")), clean_text(r.get("note")))
               for r in _rows(obj.get("formats"))]
    items = [Item(clean_text(r.get("title")), clean_text(r.get("url"))) for r in _rows(obj.get("items"))]
    return Info(clean_text(obj.get("title")) or None, size, [f for f in formats if f.id],
                [i for i in items if i.title or i.url])


class LineReader:
    """Reads one run's lines. Bounds their length and counts them against MAX_LINES, except `progress` lines (a
    long download prints about ten a second); unknown types are ignored, so a newer plugin can say more than this
    anydm understands. A line that is not JSON raises BadJson and counts too: callers skip it and read on, and a
    flood of such lines still ends in "too many lines"."""

    def __init__(self) -> None:
        self._lines = 0

    def drop(self) -> None:
        """Count a line that was never read (the stream already refused it as too long)."""
        self._lines += 1
        if self._lines > MAX_LINES:
            raise ContractError("the plugin printed too many lines")

    def feed(self, raw: bytes) -> dict[str, object] | None:
        if len(raw) > MAX_LINE:
            self.drop()
            raise LineTooLong("the plugin printed a line that is too long")
        try:
            obj = json.loads(raw)
        except ValueError as e:
            self.drop()
            raise BadJson("the plugin printed a line that is not JSON") from e
        if not isinstance(obj, dict) or obj.get("type") not in LINE_TYPES:
            self.drop()
            return None
        if obj["type"] != "progress":
            self.drop()
        return obj
