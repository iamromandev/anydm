from __future__ import annotations

import uuid
from typing import Annotated

from pydantic import Field, computed_field, model_validator

from src.core.base import BaseSchema
from src.lib.media.subtitle import TEXT_CODECS


class StreamStartRequest(BaseSchema):
    url: Annotated[
        str | None,
        Field(
            default=None,
            min_length=1,
            description=(
                "A page on a site yt-dlp supports, played at up to 1080p, "
                "or a direct http or https URL to a media file"
            ),
        ),
    ]
    torrent: Annotated[
        str | None,
        Field(
            default=None,
            min_length=1,
            description="A magnet link, an http URL to a .torrent, or a base64 .torrent file",
        ),
    ]
    task_id: Annotated[
        uuid.UUID | None,
        Field(default=None, description="A finished download, played from its file on disk"),
    ]
    file_index: Annotated[
        int | None,
        Field(
            default=None,
            ge=0,
            description=(
                "With task_id or torrent, which of the torrent's files; its largest media file by default"
            ),
        ),
    ]

    audio_language: Annotated[
        str | None,
        Field(
            default=None,
            min_length=1,
            max_length=35,
            description="The audio language to prefer, as ISO 639 or BCP 47 (en, eng, en-US)",
        ),
    ]
    audio_track: Annotated[
        int | None,
        Field(default=None, ge=0, description="The audio track to play, when the source has it"),
    ]

    @model_validator(mode="after")
    def _one_source(self) -> StreamStartRequest:
        sources = [self.url, self.torrent, self.task_id]
        if sum(source is not None for source in sources) != 1:
            raise ValueError("Provide exactly one of url, torrent or task_id")
        if self.file_index is not None and self.url is not None:
            raise ValueError("file_index goes with task_id or torrent")
        return self


class AudioTrackSchema(BaseSchema):
    """One of a source's audio tracks (#99)."""

    #: Among its audio tracks only; what ``/stream/{id}/audio`` takes.
    index: int
    language: str | None = None
    title: str | None = None
    channels: int | None = None
    codec: str | None = None
    #: The one the source marks to open with.
    default: bool = False


class SubtitleTrackSchema(BaseSchema):
    """One of a source's subtitle tracks (#100)."""

    #: Among its subtitle tracks only; what the subtitle routes take.
    index: int
    language: str | None = None
    title: str | None = None
    codec: str | None = None
    default: bool = False
    #: Shown whether or not subtitles are on.
    forced: bool = False

    @computed_field
    @property
    def text(self) -> bool:
        """Whether it can be shown: a picture track (PGS, VobSub) can't."""
        return self.codec in TEXT_CODECS


class AudioSwitchRequest(BaseSchema):
    track: Annotated[int, Field(ge=0, description="The audio track to play, from the session's audio_tracks")]


class MediaInfoSchema(BaseSchema):
    """What the player needs to choose between a download's file and a session (#94)."""

    #: A torrent's file; ``None`` for a download's one file.
    file_index: int | None = None
    filename: str
    duration_seconds: float
    has_video: bool
    #: For ``canPlayType``: ``None`` when no browser plays it from a file.
    media_type: str | None = None
    #: Where the browser fetches the file itself, with Range.
    file_url: str
    audio_tracks: list[AudioTrackSchema] = Field(default_factory=list)
    subtitle_tracks: list[SubtitleTrackSchema] = Field(default_factory=list)


class StreamSessionSchema(BaseSchema):
    session_id: str
    playlist_url: str
    status: str
    duration_seconds: float | None = None
    has_video: bool | None = None
    #: A torrent's arrive with its ``ready`` event instead, once it's probed.
    audio_tracks: list[AudioTrackSchema] | None = None
    audio_track: int | None = None
    subtitle_tracks: list[SubtitleTrackSchema] | None = None
    #: How long each segment is, the last excepted: which segment's cues hold a time.
    segment_seconds: int | None = None
