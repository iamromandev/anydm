"""rqbit's HTTP API, and the only module in this project that knows it exists.

The wrapper earns itself twice over. rqbit reports failures as a mix of JSON
bodies and plain text depending on where they happen, and its numbers are in
units nothing else here uses. ``mapping.py`` owns the arithmetic; this module
owns the transport and the failure translation.

Every method returns this project's own types, so the service layer can be
tested against a hand-written stand-in with no network and no subprocess.
"""

from __future__ import annotations

from collections.abc import Mapping, Sequence
from typing import Any

import httpx
from loguru import logger

from src.lib.torrent import error as torrent_error
from src.lib.torrent.mapping import progress_from_stats
from src.lib.torrent.protocol import FileInfo, TorrentDetails, TorrentProgress
from src.lib.torrent.source import TorrentSource


def _message_of(response: httpx.Response) -> str:
    """The most useful sentence in an rqbit error body."""
    try:
        payload = response.json()
    except ValueError:
        return (response.text or f"HTTP {response.status_code}").strip()[:200]
    if isinstance(payload, Mapping):
        for key in ("human_readable", "error", "description", "message"):
            value = payload.get(key)
            if value:
                return str(value)[:200]
    return f"HTTP {response.status_code}"


def _details_of(payload: Mapping[str, Any]) -> TorrentDetails:
    """An add or list-only response as ``TorrentDetails``.

    rqbit wraps the details in an envelope on add and returns them bare on the
    details endpoint, so both shapes are accepted. File order is the torrent's
    own order, and that position is the index every other call uses.
    """
    raw_details = payload.get("details")
    details: Mapping[str, Any] = raw_details if isinstance(raw_details, Mapping) else payload

    files = [
        FileInfo(
            index=index,
            path=str(raw.get("name") or ""),
            size_bytes=int(raw.get("length") or 0),
            included=bool(raw.get("included", True)),
        )
        for index, raw in enumerate(details.get("files") or [])
    ]

    return TorrentDetails(
        info_hash=str(details.get("info_hash") or ""),
        name=str(details.get("name") or ""),
        output_folder=str(payload.get("output_folder") or details.get("output_folder") or ""),
        files=files,
    )


class RqbitClient:
    """A ``TorrentClient`` backed by an rqbit server."""

    def __init__(
        self,
        base_url: str,
        *,
        client: httpx.AsyncClient,
        metadata_timeout_s: float,
    ) -> None:
        self._base = base_url.rstrip("/")
        self._http = client
        self._metadata_timeout = metadata_timeout_s

    @property
    def _tag(self) -> str:
        return self.__class__.__name__

    async def aclose(self) -> None:
        """Close the underlying connection pool. Call once, at shutdown."""
        await self._http.aclose()

    async def ping(self) -> bool:
        try:
            response = await self._http.get(f"{self._base}/torrents")
        except httpx.HTTPError as exc:
            logger.warning("{}|ping failed: {}", self._tag, exc)
            return False
        return response.status_code == httpx.codes.OK

    async def resolve(self, source: TorrentSource) -> TorrentDetails:
        payload = await self._add(source, params={"list_only": "true"})
        return _details_of(payload)

    async def add(
        self,
        source: TorrentSource,
        *,
        only_files: Sequence[int],
        output_folder: str,
    ) -> TorrentDetails:
        params: dict[str, str] = {"overwrite": "true", "output_folder": output_folder}
        # An empty selection means "every file", which rqbit expresses by the
        # parameter being absent. Sending an empty value is an error there.
        if only_files:
            params["only_files"] = ",".join(str(index) for index in sorted(set(only_files)))
        payload = await self._add(source, params=params)
        return _details_of(payload)

    async def list_progress(self) -> list[TorrentProgress]:
        payload = await self._request("GET", "/torrents", params={"with_stats": "true"})
        rows: list[TorrentProgress] = []
        for entry in payload.get("torrents") or []:
            stats = entry.get("stats")
            if not isinstance(stats, Mapping):
                # A torrent still being added has no stats block yet. The next
                # tick will carry it; skipping is what keeps this loop total.
                continue
            rows.append(progress_from_stats(str(entry.get("info_hash") or ""), stats))
        return rows

    async def pause(self, info_hash: str) -> None:
        await self._request("POST", f"/torrents/{info_hash}/pause")

    async def start(self, info_hash: str) -> None:
        await self._request("POST", f"/torrents/{info_hash}/start")

    async def delete(self, info_hash: str) -> None:
        await self._request("POST", f"/torrents/{info_hash}/delete")

    async def forget(self, info_hash: str) -> None:
        await self._request("POST", f"/torrents/{info_hash}/forget")

    async def _add(self, source: TorrentSource, *, params: dict[str, str]) -> dict[str, Any]:
        """POST /torrents, with the metadata timeout rather than the short one.

        Resolving a magnet means waiting for a peer to hand over the metadata,
        which legitimately takes seconds. The per-call timeout used everywhere
        else would abort a healthy add.
        """
        content = source.blob if source.blob is not None else (source.text or "").encode()
        try:
            response = await self._http.post(
                f"{self._base}/torrents",
                params=params,
                content=content,
                timeout=self._metadata_timeout,
            )
        except httpx.TimeoutException as exc:
            raise torrent_error.metadata_timeout(int(self._metadata_timeout)) from exc
        except httpx.HTTPError as exc:
            raise torrent_error.engine_unavailable(str(exc)) from exc
        return self._decoded(response)

    async def _request(
        self,
        method: str,
        path: str,
        *,
        params: dict[str, str] | None = None,
    ) -> dict[str, Any]:
        try:
            response = await self._http.request(method, f"{self._base}{path}", params=params)
        except httpx.HTTPError as exc:
            raise torrent_error.engine_unavailable(str(exc)) from exc
        return self._decoded(response)

    def _decoded(self, response: httpx.Response) -> dict[str, Any]:
        if response.status_code == httpx.codes.NOT_FOUND:
            raise torrent_error.torrent_not_found(response.request.url.path.strip("/").split("/")[-1])
        if response.status_code >= httpx.codes.BAD_REQUEST:
            raise torrent_error.engine_rejected(_message_of(response))
        if not response.content:
            return {}
        try:
            payload = response.json()
        except ValueError as exc:
            raise torrent_error.engine_rejected("Response was not JSON") from exc
        return payload if isinstance(payload, dict) else {}
