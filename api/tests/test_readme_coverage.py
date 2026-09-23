"""The README's endpoint list and environment table must cover the code.

#24 brought both in line and asked later changes to keep them there; nine PRs
later they had drifted, because nothing noticed. This fails naming whatever
the README lacks, so the next route or setting lands with its line.
"""

import re
from pathlib import Path

from fastapi.routing import APIRoute
from src.config.settings import Settings
from src.route import router

README = (Path(__file__).parents[2] / "README.md").read_text(encoding="utf-8")


def _documented(method: str, path: str) -> bool:
    # A route is listed as `METHOD /path`, optionally with its query string
    # inside the same backticks: `GET /download?page=…`.
    return re.search(rf"`{method} {re.escape(path)}[`?]", README) is not None


def test_readme_lists_every_route() -> None:
    missing = [
        f"{method} {route.path}"
        for route in router.routes
        if isinstance(route, APIRoute)
        for method in sorted(route.methods)
        if not _documented(method, route.path)
    ]

    assert not missing, f"README endpoint list lacks: {missing}"


def test_readme_lists_every_setting() -> None:
    missing = [
        name.upper()
        for name in Settings.model_fields
        if f"`{name.upper()}`" not in README
    ]

    assert not missing, f"README environment table lacks: {missing}"
