"""The player's test media come out with the tracks v0.4's issues rely on (#93).

``tests.fixtures.media`` builds them with ffmpeg's ``lavfi``, so nothing binary
is committed. ffprobe checks each file's streams, languages and flags, and the
subtitle tracks are read back out so the cue times the other tests trust are
the ones in the files.

Marked ``integration`` for the real binaries. Skipped without ffmpeg, which
CI installs.
"""

import json
import shutil
import subprocess
from pathlib import Path

import pytest

from tests.fixtures.media import CUES, DURATION_S, FORCED_CUES, SEGMENT_S, Media, build

pytestmark = [
    pytest.mark.integration,
    pytest.mark.skipif(
        shutil.which("ffmpeg") is None or shutil.which("ffprobe") is None,
        reason="needs ffmpeg and ffprobe",
    ),
]


@pytest.fixture(scope="module")
def media(tmp_path_factory: pytest.TempPathFactory) -> Media:
    return build(tmp_path_factory.mktemp("media"))


def _streams(path: Path) -> list[dict]:
    out = subprocess.run(
        ["ffprobe", "-v", "error", "-show_streams", "-show_format", "-of", "json", str(path)],
        check=True,
        capture_output=True,
        text=True,
    ).stdout
    return json.loads(out)["streams"]


def _of(streams: list[dict], kind: str) -> list[dict]:
    return [s for s in streams if s["codec_type"] == kind]


def _srt_times(path: Path, track: int) -> list[tuple[float, float, str]]:
    """A subtitle track's cues, extracted to SubRip and parsed back.

    ``-copyts`` keeps the file's own times. Without it ffmpeg re-zeroes the
    output on the input's start, which an MKV with AAC puts at -0.023 s (the
    encoder's priming), so every cue would come out 23 ms late.
    """
    text = subprocess.run(
        ["ffmpeg", "-v", "error", "-copyts", "-i", str(path), "-map", f"0:s:{track}", "-f", "srt", "-"],
        check=True,
        capture_output=True,
        text=True,
    ).stdout

    def seconds(stamp: str) -> float:
        hours, minutes, rest = stamp.split(":")
        secs, millis = rest.split(",")
        return int(hours) * 3600 + int(minutes) * 60 + int(secs) + int(millis) / 1000

    cues = []
    for block in text.strip().split("\n\n"):
        lines = block.splitlines()
        start, end = lines[1].split(" --> ")
        cues.append((seconds(start), seconds(end), " ".join(lines[2:])))
    return cues


def test_some_cues_straddle_a_segment_seam() -> None:
    seams = range(SEGMENT_S, DURATION_S, SEGMENT_S)
    straddling = {seam for seam in seams for cue in CUES if cue.start < seam < cue.end}
    assert straddling == set(seams)


def test_movie_mkv_has_two_audio_languages_and_three_subtitle_tracks(media: Media) -> None:
    streams = _streams(media.movie_mkv)
    (video,) = _of(streams, "video")
    audio = _of(streams, "audio")
    subtitles = _of(streams, "subtitle")

    assert video["codec_name"] == "h264"
    assert [a["codec_name"] for a in audio] == ["aac", "aac"]
    assert [a["tags"]["language"] for a in audio] == ["eng", "rus"]
    # The second track is the default, so "the first" and "the default" differ.
    assert [a["disposition"]["default"] for a in audio] == [0, 1]

    assert [s["codec_name"] for s in subtitles] == ["subrip", "ass", "subrip"]
    assert [s["tags"]["language"] for s in subtitles] == ["eng", "eng", "eng"]
    assert [s["disposition"]["forced"] for s in subtitles] == [0, 0, 1]
    assert [s["disposition"]["default"] for s in subtitles] == [0, 0, 0]


def test_movie_mkv_cues_are_where_the_fixture_says(media: Media) -> None:
    assert _srt_times(media.movie_mkv, 0) == [(c.start, c.end, c.text) for c in CUES]
    assert _srt_times(media.movie_mkv, 2) == [(c.start, c.end, c.text) for c in FORCED_CUES]


def test_movie_mkv_ass_track_keeps_its_styling(media: Media) -> None:
    ass = subprocess.run(
        ["ffmpeg", "-v", "error", "-i", str(media.movie_mkv), "-map", "0:s:1", "-f", "ass", "-"],
        check=True,
        capture_output=True,
        text=True,
    ).stdout
    assert "Style: Loud" in ass
    assert r"{\b1}" in ass
    # Flattened to SubRip, the overrides go and the times stay.
    flat = _srt_times(media.movie_mkv, 1)
    assert [(start, end) for start, end, _ in flat] == [(c.start, c.end) for c in CUES]


def test_hevc_mkv_is_hevc_with_surround_ac3(media: Media) -> None:
    streams = _streams(media.hevc_mkv)
    (video,) = _of(streams, "video")
    (audio,) = _of(streams, "audio")
    assert video["codec_name"] == "hevc"
    assert audio["codec_name"] == "ac3"
    assert audio["channels"] == 6


def test_movie_mp4_carries_mov_text(media: Media) -> None:
    streams = _streams(media.movie_mp4)
    assert [s["codec_name"] for s in streams] == ["h264", "aac", "mov_text"]
    assert _of(streams, "subtitle")[0]["tags"]["language"] == "eng"


def test_sidecar_files_sit_beside_their_video(media: Media) -> None:
    root = media.sidecar_video.parent
    assert media.sidecar_video.name == "Movie.mkv"
    assert [p.relative_to(root).as_posix() for p in media.sidecars] == [
        "Movie.en.srt",
        "Subs/2_English.srt",
    ]
    assert not _of(_streams(media.sidecar_video), "subtitle")
    for sidecar in media.sidecars:
        assert "00:00:01,000 --> 00:00:02,500" in sidecar.read_text()


def test_season_files_come_in_natural_order_which_text_order_breaks(media: Media) -> None:
    names = [p.name for p in media.season]
    assert names == ["Show.S01E2.mkv", "Show.S01E10.mkv"]
    # What #97's natural sort has to fix: as text, E10 comes first.
    assert sorted(names) == ["Show.S01E10.mkv", "Show.S01E2.mkv"]
    for episode in media.season:
        assert _of(_streams(episode), "video")
