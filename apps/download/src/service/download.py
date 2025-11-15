from pathlib import Path
from typing import Any
from urllib.parse import quote

from fastapi import HTTPException, Request
from fastapi.responses import FileResponse
from loguru import logger
from pydantic import HttpUrl
from yt_dlp import YoutubeDL

from src.core.base import BaseService
from src.core.constant import FORMAT_FIELDS, META_FIELDS
from src.core.format import serialize
from src.core.type import SafeFileResponse, State
from src.data.repo.interface.task import TaskRepo
from src.data.schema.task import TaskCreateSchema, TaskOutSchema, TaskUpdateSchema

MEDIA_DIR = Path("media")
MEDIA_DIR.mkdir(exist_ok=True)


class DownloadService(BaseService):
    _task_db_repo: TaskRepo

    def __init__(self, task_db_repo: TaskRepo) -> None:
        super().__init__()
        self._task_db_repo = task_db_repo

    async def _categorize_and_sort_formats(
        self, formats: list[dict[str, Any]]
    ) -> dict[str, list[dict[str, Any]]]:
        video_formats = []
        audio_formats = []
        muxed_formats = []

        for format in formats:
            vcodec = format.get("vcodec")
            acodec = format.get("acodec")

            if vcodec != "none" and acodec != "none":
                muxed_formats.append(format)
            elif vcodec != "none":
                video_formats.append(format)
            elif acodec != "none":
                audio_formats.append(format)

        # Sort video formats by resolution descending, then by bitrate
        video_formats.sort(key=lambda x: (x.get("height", 0), x.get("tbr", 0)), reverse=True)

        # Sort audio formats by abr (audio bitrate) descending
        audio_formats.sort(key=lambda x: x.get("abr", 0), reverse=True)

        # Sort muxed formats by resolution descending, then by bitrate
        muxed_formats.sort(key=lambda x: (x.get("height", 0), x.get("tbr", 0)), reverse=True)

        return {
            "video": video_formats,
            "audio": audio_formats,
            "muxed": muxed_formats
        }

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

    async def extract_meta(self, request: Request, url: HttpUrl) -> dict[str, Any]:
        task: TaskOutSchema | None = await self._task_db_repo.get_by_input(
            key="url", value=serialize(url)
        )
        if not task:
            task = await self._task_db_repo.create(
                TaskCreateSchema(
                    input={
                        "url": serialize(url)
                    }
                )
            )

        options = {
            "quiet": False,
            "skip_download": True,
            "extract_flat": False,
            "noplaylist": True,
        }

        try:
            with YoutubeDL(options) as ydl:
                info = ydl.extract_info(str(url), download=False)
        except Exception as e:
            raise HTTPException(status_code=500, detail=f"yt-dlp metadata extraction failed: {e}")

        if not info:
            raise HTTPException(status_code=404, detail="Metadata not found")

        filtered_formats = [
            {k: v for k, v in f.items() if k in FORMAT_FIELDS}
            for f in info.get("formats", [])
            if f.get("url")
        ]
        categorized_formats = await self._categorize_and_sort_formats(filtered_formats)

        filtered_info = {k: v for k, v in info.items() if k in META_FIELDS}
        filtered_info["formats"] = categorized_formats

        await self._task_db_repo.update(
            target=task.id,
            data=TaskUpdateSchema(
                state=State.COMPLETED,
                output={
                    "info": filtered_info
                }
            )
        )

        return filtered_info

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
