import os
from pathlib import Path

from fastapi import HTTPException, Request
from fastapi.responses import FileResponse
from pydantic import HttpUrl
from yt_dlp import YoutubeDL

from src.core.base import BaseService
from src.core.format import serialize

MEDIA_DIR = Path("media")
MEDIA_DIR.mkdir(exist_ok=True)


class DownloadService(BaseService):

    def __init__(self) -> None:
        super().__init__()

    async def _download(self, url: HttpUrl) -> tuple[str, str]:
        youtube_url = serialize(url)

        ydl_opts = {
            "outtmpl": str(MEDIA_DIR / "%(title)s.%(ext)s"),
            "format": "bestvideo+bestaudio/best",
            "merge_output_format": "mp4",
            "noplaylist": True,
            "quiet": True,
        }

        with YoutubeDL(ydl_opts) as ydl:
            info = ydl.extract_info(youtube_url, download=True)
            title = info.get("title", "video")
            filename = ydl.prepare_filename(info)
            filepath = os.path.splitext(filename)[0] + ".mp4"
            return filepath, title

    async def download(self, request: Request, url: HttpUrl) -> FileResponse:
        try:
            filepath, title = await self._download(url)
        except Exception as e:
            raise HTTPException(status_code=500, detail=f"yt-dlp failed: {e}")

        if not os.path.exists(filepath):
            raise HTTPException(status_code=404, detail="Download failed")

        return FileResponse(
            filepath,
            media_type="video/mp4",
            filename=f"{title}.mp4",
            headers={"Content-Disposition": f'attachment; filename="{title}.mp4"'},
        )
