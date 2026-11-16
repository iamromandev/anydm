"""The user's Netscape cookies file: checked before any work, copied privately for yt-dlp. The file holds session
secrets, so no message here carries its content, only its path."""

import os
import re
import tempfile
from dataclasses import dataclass
from pathlib import Path

from src.core.errors import UsageError
from src.core.names import clean_display

MAX_BYTES = 10 * 1024 * 1024
LOGIN_HINT_NO_COOKIES = "this site needs a login: pass --cookies FILE (see README)"
LOGIN_HINT_EXPIRED = "the cookies may be expired: export them again"
_HEADERS = ("# Netscape HTTP Cookie File", "# HTTP Cookie File")
_FIELDS = 7
_HTTPONLY = "#HttpOnly_"
_BOM = b"\xef\xbb\xbf"
_EXPIRY = re.compile(r"[0-9]+(?:\.[0-9]+)?")


@dataclass(frozen=True)
class Cookies:
    path: Path
    warning: str | None


def _entry_ok(line: str) -> bool | None:
    """yt-dlp's rule for one line (YoutubeDLCookieJar.load): None for a comment or a blank line, else whether it is
    an entry of 7 tab-separated fields with an empty or numeric expiry. yt-dlp prints a line it rejects, raw, on
    stderr, so every such line must be caught here."""
    line = line.removeprefix(_HTTPONLY)
    if line.startswith("#") or not line.strip():
        return None
    fields = line.split("\t")
    return len(fields) == _FIELDS and (not fields[4] or _EXPIRY.fullmatch(fields[4]) is not None)


def _netscape(data: bytes) -> bool:
    """Every line is a comment, blank or valid entry (yt-dlp's rules), and the file has the header or an entry."""
    try:
        text = data.decode("utf-8-sig")  # yt-dlp reads utf-8 and fails on anything else; a BOM is dropped (copy_private)
    except UnicodeDecodeError:
        return False
    lines = text.replace("\r\n", "\n").replace("\r", "\n").split("\n")
    verdicts = [_entry_ok(ln) for ln in lines]
    if False in verdicts:
        return False
    first = next((ln.strip() for ln in lines if ln.strip()), None)
    return first in _HEADERS or True in verdicts


def check_cookies(path: Path) -> Cookies:
    """The absolute, symlink-free path of a usable cookies file. Raises UsageError (exit 2) naming the path when the
    file is missing, not a regular file, unreadable, over MAX_BYTES or not Netscape format. `warning` is set when
    anyone but the owner can read the file."""
    try:
        target = path.expanduser().resolve()
    except (OSError, RuntimeError, ValueError) as e:  # a symlink loop, an unknown ~user
        raise UsageError(f"cookies file {clean_display(str(path))}: cannot be resolved ({type(e).__name__})") from e
    shown = clean_display(str(target))
    try:
        st = target.stat()
    except FileNotFoundError:
        raise UsageError(f"cookies file not found: {shown}") from None
    except OSError as e:
        raise UsageError(f"cannot read cookies file {shown}: {e.strerror or type(e).__name__}") from e
    if not target.is_file():
        raise UsageError(f"cookies file is not a regular file: {shown}")
    if st.st_size > MAX_BYTES:
        raise UsageError(f"cookies file is too big (over {MAX_BYTES // (1024 * 1024)} MB): {shown}")
    try:
        with target.open("rb") as f:
            data = f.read(MAX_BYTES + 1)
    except OSError as e:
        raise UsageError(f"cannot read cookies file {shown}: {e.strerror or type(e).__name__}") from e
    if len(data) > MAX_BYTES:
        raise UsageError(f"cookies file is too big (over {MAX_BYTES // (1024 * 1024)} MB): {shown}")
    if not _netscape(data):
        raise UsageError(f"not a Netscape cookies file: {shown}")
    warning = None
    if st.st_mode & 0o077:
        warning = f"cookies file {shown} can be read by other users; run chmod 600 on it"
    return Cookies(target, warning)


def copy_private(src: Path, dir: Path | None = None) -> Path:
    """A 0600 copy of `src` in `dir` (the temp folder by default). The caller removes it."""
    fd, name = tempfile.mkstemp(prefix="anydm-cookies-", suffix=".txt", dir=dir)
    copy = Path(name)
    try:
        with os.fdopen(fd, "wb") as out:
            out.write(src.read_bytes().removeprefix(_BOM))  # yt-dlp would print a BOM-led header as a bad line
    except BaseException:
        copy.unlink(missing_ok=True)
        raise
    return copy
