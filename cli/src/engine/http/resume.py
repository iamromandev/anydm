import json
import os
from dataclasses import asdict, dataclass, field
from pathlib import Path

from src.engine.http.probe import ProbeResult

VERSION = 1


@dataclass
class Segment:
    start: int
    end: int
    done: int = 0

    @property
    def length(self) -> int:
        return self.end - self.start + 1


@dataclass
class Record:
    url: str
    final_url: str
    size: int | None
    etag: str | None
    last_modified: str | None
    segments: list[Segment] = field(default_factory=list)  # empty: a single stream, which cannot resume

    def matches(self, p: ProbeResult) -> bool:
        return (
            self.size == p.size
            and self.etag == p.etag
            and self.last_modified == p.last_modified
            and self.size is not None
            and bool(self.segments)
        )


def record_path(final: Path) -> Path:
    return final.with_name(final.name + ".part.json")


def part_path(final: Path) -> Path:
    return final.with_name(final.name + ".part")


def lock_path(final: Path) -> Path:
    return final.with_name(final.name + ".part.lock")


def read(final: Path) -> Record | None:
    """The saved record, or None when it is missing, unreadable or from another version."""
    try:
        raw = json.loads(record_path(final).read_text())
        if raw["v"] != VERSION:
            return None
        return Record(
            url=raw["url"],
            final_url=raw["final_url"],
            size=raw["size"],
            etag=raw["etag"],
            last_modified=raw["last_modified"],
            segments=[Segment(s["start"], s["end"], s["done"]) for s in raw["segments"]],
        )
    except (OSError, ValueError, KeyError, TypeError):
        return None


def write(final: Path, record: Record) -> None:
    path = record_path(final)
    tmp = path.with_name(path.name + ".tmp")
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)  # holds URLs that may carry credentials
    with os.fdopen(fd, "w") as f:
        os.fchmod(f.fileno(), 0o600)
        f.write(json.dumps({"v": VERSION, **asdict(record)}))
    os.replace(tmp, path)
