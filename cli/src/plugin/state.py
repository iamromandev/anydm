"""The installed plugins: `plugins.toml` beside config.toml. It is the user's approval record (which image, which
permissions), so it is read from this one file only, never from `.env` or the environment, and it is written whole,
atomically, with mode 0600."""

import contextlib
import os
import tempfile
import tomllib
from pathlib import Path
from typing import TYPE_CHECKING, Any

from src.core.errors import UsageError
from src.plugin.contract import CAPABILITIES, NAME_RE, PERMISSIONS, RESERVED
from src.plugin.model import Plugin, is_pinned, media_plugin

if TYPE_CHECKING:
    from src.config.settings import Settings


def state_path() -> Path:
    from src.config.settings import config_path  # lazy: pydantic is slow to import

    return config_path().with_name("plugins.toml")


def _image_ok(image: str) -> bool:
    from src.config.settings import _IMAGE_REF  # lazy, as above

    return _IMAGE_REF.fullmatch(image) is not None


def _string(value: str) -> str:
    """A TOML basic string: quotes, backslashes and control characters escaped, anything else as it is."""
    out = ['"']
    for c in value:
        if c == '"':
            out.append('\\"')
        elif c == "\\":
            out.append("\\\\")
        elif c < " " or c == "\x7f":
            out.append(f"\\u{ord(c):04x}")
        else:
            out.append(c)
    out.append('"')
    return "".join(out)


def _array(values: list[str]) -> str:
    return "[" + ", ".join(_string(v) for v in values) + "]"


def render(plugins: list[Plugin]) -> str:
    """The whole file for `plugins` (in routing order). Raises ValueError for a record the file could not hold."""
    lines = [f"order = {_array([p.name for p in plugins])}"]
    for p in plugins:
        if not NAME_RE.fullmatch(p.name) or p.name in RESERVED:
            raise ValueError(f"not a plugin name: {p.name!r}")
        if not _image_ok(p.image):
            raise ValueError(f"not an image reference: {p.image!r}")
        if not is_pinned(p.image):
            raise ValueError(f"image of plugin {p.name} is not pinned by digest: {p.image!r}")
        lines += [
            "",
            f"[plugins.{p.name}]",
            f"image = {_string(p.image)}",
            f"version = {_string(p.version)}",
            f"capabilities = {_array(sorted(p.capabilities))}",
            f"permissions = {_array(sorted(p.permissions))}",
        ]
    return "\n".join(lines) + "\n"


def _sync_dir(folder: Path) -> None:
    """Make the rename itself durable: flush the directory entry. A platform that cannot open a directory skips it."""
    try:
        fd = os.open(folder, os.O_RDONLY)
    except OSError:
        return
    try:
        os.fsync(fd)
    except OSError:
        pass  # some filesystems refuse fsync on a directory; the file itself is already flushed
    finally:
        os.close(fd)


def save(plugins: list[Plugin]) -> None:
    """Write `plugins` (in routing order) to the state file: a temporary file in the same folder, then a rename, so
    a reader sees the old file or the new one. Mode 0600 whatever the umask."""
    path = state_path()
    text = render(plugins)
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, tmp = tempfile.mkstemp(prefix=".plugins-", suffix=".tmp", dir=path.parent)  # created 0600
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            os.fchmod(f.fileno(), 0o600)
            f.write(text)
            f.flush()
            os.fsync(f.fileno())
        os.replace(tmp, path)
        _sync_dir(path.parent)
    except BaseException:
        with contextlib.suppress(OSError):
            os.unlink(tmp)
        raise


def _strings(raw: object, allowed: frozenset[str] | None) -> frozenset[str] | None:
    if not isinstance(raw, list) or not all(isinstance(x, str) for x in raw):
        return None
    if allowed is not None and not set(raw) <= allowed:
        return None
    return frozenset(raw)


def _plugin(name: str, raw: object) -> Plugin | None:
    if not isinstance(raw, dict) or not NAME_RE.fullmatch(name) or name in RESERVED:
        return None
    image, version = raw.get("image"), raw.get("version")
    capabilities = _strings(raw.get("capabilities"), CAPABILITIES)
    permissions = _strings(raw.get("permissions"), PERMISSIONS)
    if not isinstance(image, str) or not _image_ok(image) or not isinstance(version, str):
        return None
    if not capabilities or permissions is None:
        return None
    return Plugin(name, image, version, capabilities, permissions)


_cache: tuple[Path, tuple[int, int, int], list[Plugin]] | None = None


def load() -> list[Plugin]:
    """The installed plugins in routing order (media is not among them). Raises UsageError, naming the file, when
    it cannot be read or does not hold what `save` writes.

    A file that has not changed (same inode, size and modification time: `save` always renames a new file into
    place) is not read and parsed again, so the TUI can ask once per row. The list is the caller's own."""
    global _cache
    path = state_path()
    try:
        st = path.stat()
    except FileNotFoundError:
        return []
    except OSError as e:
        raise UsageError(f"invalid plugin state {path}: {type(e).__name__}") from e
    stamp = (st.st_ino, st.st_size, st.st_mtime_ns)
    if _cache is not None and _cache[0] == path and _cache[1] == stamp:
        return list(_cache[2])
    plugins = _read(path)
    _cache = (path, stamp, plugins)
    return list(plugins)


def _read(path: Path) -> list[Plugin]:
    try:
        text = path.read_text(encoding="utf-8")
    except FileNotFoundError:
        return []
    except (OSError, UnicodeDecodeError) as e:
        raise UsageError(f"invalid plugin state {path}: {type(e).__name__}") from e
    try:
        data: dict[str, Any] = tomllib.loads(text)
    except tomllib.TOMLDecodeError as e:
        raise UsageError(f"invalid plugin state {path}: {e}") from e
    table, order = data.get("plugins", {}), data.get("order", [])
    if not isinstance(table, dict) or not isinstance(order, list) or not all(isinstance(n, str) for n in order):
        raise UsageError(f"invalid plugin state {path}: 'plugins' must be a table and 'order' a list of names")
    found: dict[str, Plugin] = {}
    for name, raw in table.items():
        plugin = _plugin(name, raw)
        if plugin is None:
            raise UsageError(f"invalid plugin state {path}: bad entry for plugin {name!r}")
        if not is_pinned(plugin.image):
            raise UsageError(
                f"invalid plugin state {path}: the image of plugin {name!r} is not pinned by digest "
                f"(repo@sha256:... or an image id); add the plugin again with `anydm plugin add`"
            )
        found[name] = plugin
    unknown = [n for n in order if n not in found]
    if unknown:
        raise UsageError(f"invalid plugin state {path}: 'order' names plugins that are not installed: {unknown}")
    listed = list(dict.fromkeys(order))
    return [found[n] for n in listed] + [p for n, p in found.items() if n not in listed]


def routing(settings: Settings) -> list[Plugin]:
    """Every plugin in the order anydm asks them: the installed ones, then the built-in media plugin."""
    return [*load(), media_plugin(settings)]
