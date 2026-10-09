"""The refusal of an add that the list already holds."""

from __future__ import annotations

from typing import Any

from src.core.error import Error, ErrorDetail
from src.data.repo.download.described import describe


def already_held(download: Any) -> Error:
    """409, naming the download that already has it.

    The first detail carries it for a client to offer Open: ``subject`` is its id,
    ``description`` its title, and ``fields`` its status. A video a playlist or
    channel holds adds a second, for the collection: ``subject`` is its id,
    ``description`` its title, and ``fields`` is ``["collection"]``.
    """
    title = describe(download).title
    status = download.status.value
    details = [ErrorDetail(subject=str(download.id), description=title, fields=[status])]
    message = f"Already in your list: {title} ({status})"
    collection = download.parent if download.parent_id is not None else None
    if collection is not None:
        collection_title = collection.media.title if collection.media is not None else ""
        details.append(ErrorDetail(subject=str(collection.id), description=collection_title, fields=["collection"]))
        message += f", in {collection_title}" if collection_title else ", in a playlist"
    return Error.conflict(message=message, details=details)
