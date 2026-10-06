"""The refusal of an add that the list already holds."""

from __future__ import annotations

from typing import Any

from src.core.error import Error, ErrorDetail
from src.data.repo.download.described import describe


def already_held(download: Any) -> Error:
    """409, naming the download that already has it.

    The one detail carries it for a client to offer Open: ``subject`` is its id,
    ``description`` its title, and ``fields`` its status.
    """
    title = describe(download).title
    status = download.status.value
    return Error.conflict(
        message=f"Already in your list: {title} ({status})",
        details=[ErrorDetail(subject=str(download.id), description=title, fields=[status])],
    )
