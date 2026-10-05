"""The catalog rows a download is added from: one ``Url`` per address, one ``Provider`` per site or kind, one ``Source`` per way an address is used.

Each is found or made, never duplicated: an address is one ``Url`` row however
many downloads point at it, which is what lets "is this already added" be a
lookup by its normalized hash. The single-row helpers serve one add; the bulk
ones a collection's thousands of videos, in a fixed number of statements.
"""

from __future__ import annotations

from collections.abc import Iterable, Sequence
from typing import Any
from urllib.parse import urlsplit

from src.core.url import normalize_url, url_hash
from src.data.db.model import Provider, Source, Url
from src.data.type import SourceKind


def _url_fields(address: str) -> dict[str, Any]:
    normalized = normalize_url(address)
    parts = urlsplit(address)
    return {
        "value": address,
        "normalized": normalized,
        "normalized_hash": url_hash(normalized),
        "scheme": parts.scheme,
        "host": parts.hostname,
        "port": parts.port,
        "path": parts.path or None,
        "query": parts.query or None,
        "fragment": parts.fragment or None,
    }


def address_hash(address: str) -> str:
    """What two spellings of one address share: the key ``Url`` rows are found by."""
    return url_hash(normalize_url(address))


async def url_row(address: str, conn: Any = None) -> Url:
    """The one ``Url`` row for this address, made the first time it is seen."""
    fields = _url_fields(address)
    url, _ = await Url.get_or_create(normalized_hash=fields.pop("normalized_hash"), defaults=fields, using_db=conn)
    return url


async def provider_row(name: str, conn: Any = None) -> Provider:
    """The provider named ``name`` (a site's extractor key, ``http`` or ``torrent``), slugged by its lowercase."""
    provider, _ = await Provider.get_or_create(slug=name.lower(), defaults={"name": name}, using_db=conn)
    return provider


async def source_row(provider: Provider, url: Url, kind: SourceKind, conn: Any = None) -> Source:
    source, _ = await Source.get_or_create(provider=provider, url=url, kind=kind, using_db=conn)
    return source


async def url_rows(addresses: Iterable[str], conn: Any) -> dict[str, Url]:
    """``Url`` rows for many addresses, by normalized hash: one insert of the new ones, one read of all."""
    fresh = {}
    for address in addresses:
        fields = _url_fields(address)
        fresh.setdefault(fields["normalized_hash"], fields)
    if not fresh:
        return {}
    await Url.bulk_create(
        [Url(**fields) for fields in fresh.values()], batch_size=500, ignore_conflicts=True, using_db=conn
    )
    return {url.normalized_hash: url for url in await Url.filter(normalized_hash__in=list(fresh)).using_db(conn)}


async def source_rows(provider: Provider, urls: Sequence[Url], kind: SourceKind, conn: Any) -> dict[Any, Source]:
    """``Source`` rows for many urls of one provider and kind, by url id."""
    if not urls:
        return {}
    await Source.bulk_create(
        [Source(provider=provider, url=url, kind=kind) for url in urls],
        batch_size=500,
        ignore_conflicts=True,
        using_db=conn,
    )
    found = Source.filter(provider=provider, kind=kind, url_id__in=[url.id for url in urls]).using_db(conn)
    return {source.url_id: source for source in await found}
