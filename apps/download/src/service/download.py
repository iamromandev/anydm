from pathlib import Path
from typing import Any
from urllib.parse import quote

from fastapi import HTTPException, Request
from fastapi.responses import FileResponse
from loguru import logger
from pydantic import HttpUrl
from yt_dlp import YoutubeDL

from src.core.base import BaseService
from src.core.format import serialize
from src.core.type import SafeFileResponse

MEDIA_DIR = Path("media")
MEDIA_DIR.mkdir(exist_ok=True)


class DownloadService(BaseService):

    def __init__(self) -> None:
        super().__init__()

    def _clean_headers(self, headers: dict[str, Any]) -> dict[str, Any]:
        """Clean headers to contain only ASCII characters"""
        cleaned = {}
        for key, value in headers.items():
            # Remove non-ASCII characters from header values
            if isinstance(value, str):
                cleaned_value = value.encode('ascii', 'ignore').decode('ascii')
                cleaned[key] = cleaned_value
            else:
                cleaned[key] = value
        return cleaned

    async def _extract_metadata(self, url: HttpUrl) -> tuple[HttpUrl, str]:
        options = {"quiet": True, "skip_download": True}
        with YoutubeDL(options) as ydl:
            info = ydl.extract_info(str(url), download=False)
            # pick best format that has height or fallback
            formats = [f for f in info.get("formats", []) if f.get("height")]
            best_format = max(formats, key=lambda f: f["height"]) if formats else info
            return HttpUrl(best_format["url"]), info.get("title", "video")

    async def _download(self, url: HttpUrl) -> tuple[Path, str]:
        direct_url, title = await self._extract_metadata(url)
        filename = f"{title}.mp4"
        output_path = MEDIA_DIR / filename

        ydl_opts = {
            "outtmpl": str(output_path),
            "format": "bestvideo+bestaudio/best",
            "merge_output_format": "mp4",
            "quiet": True,
        }

        with YoutubeDL(ydl_opts) as ydl:
            logger.debug(f"{self._tag}|_download(): downloading {title}")
            ydl.download([serialize(direct_url)])

        if not output_path.exists():
            raise HTTPException(status_code=404, detail="Download failed")

        return output_path, filename

    async def download(self, request: Request, url: HttpUrl) -> FileResponse:
        """
        Public endpoint handler that downloads and returns the video file.
        """
        try:
            filepath, filename = await self._download(url)
        except HTTPException:
            raise
        except Exception as e:
            logger.error(f"{self._tag}|download(): yt-dlp failed - {e}")
            raise HTTPException(status_code=500, detail=f"yt-dlp failed: {e}")
        headers = {
            "Content-Disposition": f"attachment; filename*=UTF-8''{quote(filename[:200])}"
        }
        return SafeFileResponse(
            filepath,
            media_type="video/mp4",
            filename=filename,
            headers=headers,
        )
