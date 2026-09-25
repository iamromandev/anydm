"""The player's test media, generated with ffmpeg's ``lavfi`` so nothing binary is committed (#93).

Each file carries what one of v0.4's issues has to handle:

- ``movie.mkv``: H.264, two AAC tracks (``eng``, then ``rus`` marked
  default), and three subtitle tracks: SubRip, styled ASS, and a forced SubRip.
- ``hevc.mkv``: HEVC with 5.1 AC-3, which no browser plays natively.
- ``movie.mp4``: H.264/AAC with a mov_text track.
- ``sidecar/Movie.mkv`` with ``Movie.en.srt`` beside it and
  ``Subs/2_English.srt`` below it, and no subtitles inside.
- ``season/Show.S01E2.mkv`` and ``Show.S01E10.mkv``, which text order puts
  the wrong way round.

The cues are at known times, and some straddle each seam between the
player's 6 s segments, so a test can check a cue survives the cut.

As a script, ``python -m tests.fixtures.media <dir>`` writes them into ``dir``.
"""

import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path

#: The player's default segment length (``STREAM_SEGMENT_SECONDS``).
SEGMENT_S = 6
DURATION_S = 20
EPISODE_S = 4


@dataclass(frozen=True)
class Cue:
    start: float
    end: float
    text: str


#: One cue straddles each seam at 6, 12 and 18 s.
CUES = (
    Cue(1.0, 2.5, "cue 1"),
    Cue(5.0, 7.0, "cue 2 across 6"),
    Cue(9.0, 10.5, "cue 3"),
    Cue(11.5, 12.5, "cue 4 across 12"),
    Cue(15.0, 16.0, "cue 5"),
    Cue(17.0, 19.0, "cue 6 across 18"),
)

FORCED_CUES = (
    Cue(3.0, 4.0, "forced 1"),
    Cue(13.0, 14.0, "forced 2"),
)


@dataclass(frozen=True)
class Media:
    root: Path
    movie_mkv: Path
    hevc_mkv: Path
    movie_mp4: Path
    sidecar_video: Path
    sidecars: tuple[Path, ...]
    season: tuple[Path, ...]


def build(root: Path) -> Media:
    root.mkdir(parents=True, exist_ok=True)
    srt = _write(root / "cues.srt", _srt(CUES))
    forced = _write(root / "forced.srt", _srt(FORCED_CUES))
    ass = _write(root / "cues.ass", _ass(CUES))

    movie_mkv = root / "movie.mkv"
    _ffmpeg(
        *_video(DURATION_S), *_tone(440, DURATION_S), *_tone(880, DURATION_S),
        "-i", str(srt), "-i", str(ass), "-i", str(forced),
        "-map", "0:v", "-map", "1:a", "-map", "2:a", "-map", "3", "-map", "4", "-map", "5",
        *_h264(), "-c:a", "aac",
        "-c:s:0", "srt", "-c:s:1", "ass", "-c:s:2", "srt",
        "-metadata:s:a:0", "language=eng", "-metadata:s:a:1", "language=rus",
        "-disposition:a:0", "0", "-disposition:a:1", "default",
        "-metadata:s:s:0", "language=eng", "-metadata:s:s:0", "title=English",
        "-metadata:s:s:1", "language=eng", "-metadata:s:s:1", "title=English (styled)",
        "-metadata:s:s:2", "language=eng", "-metadata:s:s:2", "title=English (forced)",
        "-disposition:s:0", "0", "-disposition:s:1", "0", "-disposition:s:2", "forced",
        # Otherwise the muxer marks the first subtitle track default on its own.
        "-default_mode", "passthrough",
        str(movie_mkv),
    )

    hevc_mkv = root / "hevc.mkv"
    _ffmpeg(
        *_video(DURATION_S), *_tone(440, DURATION_S),
        "-map", "0:v", "-map", "1:a",
        "-c:v", "libx265", "-preset", "ultrafast", "-x265-params", "log-level=error",
        "-af", "pan=5.1|c0=c0|c1=c0|c2=c0|c3=c0|c4=c0|c5=c0", "-c:a", "ac3",
        str(hevc_mkv),
    )

    movie_mp4 = root / "movie.mp4"
    _ffmpeg(
        *_video(DURATION_S), *_tone(440, DURATION_S), "-i", str(srt),
        "-map", "0:v", "-map", "1:a", "-map", "2",
        *_h264(), "-c:a", "aac", "-c:s", "mov_text",
        "-metadata:s:s:0", "language=eng", "-movflags", "+faststart",
        str(movie_mp4),
    )

    sidecar_dir = root / "sidecar"
    (sidecar_dir / "Subs").mkdir(parents=True, exist_ok=True)
    sidecar_video = sidecar_dir / "Movie.mkv"
    _ffmpeg(*_video(EPISODE_S), *_tone(440, EPISODE_S), *_h264(), "-c:a", "aac", str(sidecar_video))
    sidecars = (
        _write(sidecar_dir / "Movie.en.srt", _srt(CUES)),
        _write(sidecar_dir / "Subs" / "2_English.srt", _srt(CUES)),
    )

    season_dir = root / "season"
    season_dir.mkdir(exist_ok=True)
    season = tuple(season_dir / f"Show.S01E{episode}.mkv" for episode in (2, 10))
    for episode in season:
        _ffmpeg(*_video(EPISODE_S), *_tone(440, EPISODE_S), *_h264(), "-c:a", "aac", str(episode))

    return Media(root, movie_mkv, hevc_mkv, movie_mp4, sidecar_video, sidecars, season)


def _ffmpeg(*args: str) -> None:
    subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", *args], check=True)


def _video(seconds: int) -> list[str]:
    return ["-f", "lavfi", "-i", f"testsrc=size=640x360:rate=25:duration={seconds}"]


def _tone(frequency: int, seconds: int) -> list[str]:
    return ["-f", "lavfi", "-i", f"sine=frequency={frequency}:duration={seconds}"]


def _h264() -> list[str]:
    # A keyframe every 2 s, so the 6 s segments don't all start on one.
    return ["-c:v", "libx264", "-preset", "ultrafast", "-g", "50", "-pix_fmt", "yuv420p"]


def _write(path: Path, text: str) -> Path:
    path.write_text(text)
    return path


def _srt(cues: tuple[Cue, ...]) -> str:
    def stamp(seconds: float) -> str:
        millis = round(seconds * 1000)
        return f"{millis // 3_600_000:02}:{millis // 60_000 % 60:02}:{millis // 1000 % 60:02},{millis % 1000:03}"

    return "".join(
        f"{n}\n{stamp(cue.start)} --> {stamp(cue.end)}\n{cue.text}\n\n" for n, cue in enumerate(cues, 1)
    )


def _ass(cues: tuple[Cue, ...]) -> str:
    def stamp(seconds: float) -> str:
        centis = round(seconds * 100)
        return f"{centis // 360_000}:{centis // 6000 % 60:02}:{centis // 100 % 60:02}.{centis % 100:02}"

    style_format = (
        "Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, "
        "Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, "
        "Shadow, Alignment, MarginL, MarginR, MarginV, Encoding"
    )
    header = "\n".join(
        [
            "[Script Info]",
            "ScriptType: v4.00+",
            "PlayResX: 640",
            "PlayResY: 360",
            "",
            "[V4+ Styles]",
            style_format,
            "Style: Default,Arial,20,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,1,0,2,10,10,10,1",
            "Style: Loud,Arial,28,&H0000FFFF,&H000000FF,&H00000000,&H00000000,1,0,0,0,100,100,0,0,1,2,0,8,10,10,10,1",
            "",
            "[Events]",
            "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text",
        ]
    )
    events = "\n".join(
        f"Dialogue: 0,{stamp(cue.start)},{stamp(cue.end)},Loud,,0,0,0,,{{\\b1}}{cue.text}" for cue in cues
    )
    return f"{header}\n{events}\n"


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("usage: python -m tests.fixtures.media <dir>")
    print(build(Path(sys.argv[1])))
