import re

# C0 and C1 controls, and the bidi overrides and isolates that make a name read differently from how it is spelled
_CONTROL = re.compile(r"[\x00-\x1f\x7f-\x9f\u202a-\u202e\u2066-\u2069]")
_NAME_MAX = 255
# room for the longest derived name: `name.part.json.tmp` (14 bytes) and a ` (999)` counter (6 bytes)
_MAX_BYTES = _NAME_MAX - 14 - 6
_RESERVED_ENDINGS = (".part", ".part.json", ".part.lock", ".part.json.tmp")


def clean_name(raw: str, fallback: str = "download") -> str:
    """A safe file name from untrusted text: basename only, no control or bidi-override characters, short enough
    for its partial files."""
    name = _CONTROL.sub("", raw.replace("\\", "/")).rsplit("/", 1)[-1]
    name = name.lstrip(". ").rstrip(" ")
    if not name or set(name) <= {"."}:
        return fallback
    if len(name.encode()) > _MAX_BYTES:
        stem, dot, ext = name.rpartition(".")
        if not dot or len(ext.encode()) > 32:
            stem, ext = name, ""
        else:
            ext = "." + ext
        stem = stem.encode()[: _MAX_BYTES - len(ext.encode())].decode(errors="ignore")
        name = stem + ext
    if name.endswith(_RESERVED_ENDINGS):
        name += "_"
    return name
