"""The player's test media come out with the tracks v0.4's issues rely on (#93).

``tests.fixtures.media`` builds them with ffmpeg's ``lavfi``, so nothing binary
is committed. ffprobe checks each file's streams, languages and flags, and the
subtitle tracks are read back out so the cue times the other tests trust are
the ones in the files.

Marked ``integration`` for the real binaries. Skipped without ffmpeg, which
CI installs.
"""

import json
import math
import re
import shutil
import subprocess
from array import array
from itertools import pairwise
from pathlib import Path

import pytest
from src.lib.media.audio import pick_audio_track
from src.lib.media.ffmpeg import segment_args, subtitle_args, subtitle_file_args
from src.lib.media.ffprobe import parse_probe_output
from src.lib.media.sidecar import decode_subtitles, folder_listing, match_sidecars
from src.lib.media.source import MediaInput

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


# --- audio tracks (#99) ---------------------------------------------------------


def _probe_json(path: Path) -> str:
    return subprocess.run(
        ["ffprobe", "-v", "quiet", "-print_format", "json", "-show_format", "-show_streams", str(path)],
        check=True,
        capture_output=True,
        text=True,
    ).stdout


def _tone_hz(segment: Path) -> float:
    """The pitch of a segment's audio, from how often it crosses zero: two crossings a cycle."""
    pcm = subprocess.run(
        ["ffmpeg", "-v", "error", "-i", str(segment), "-map", "0:a:0", "-ac", "1", "-ar", "16000",
         "-f", "s16le", "-"],
        check=True,
        capture_output=True,
    ).stdout
    samples = array("h", pcm)
    crossings = sum(1 for a, b in pairwise(samples) if (a < 0) != (b < 0))
    return crossings / 2 / (len(samples) / 16000)


def test_movie_mkv_probes_as_two_audio_tracks(media: Media) -> None:
    tracks = parse_probe_output(_probe_json(media.movie_mkv)).audio_tracks

    assert [(t.index, t.language, t.default) for t in tracks] == [(0, "eng", False), (1, "rus", True)]
    assert pick_audio_track(tracks, "en") == 0
    assert pick_audio_track(tracks) == 1


@pytest.mark.parametrize(("track", "hz"), [(0, 440), (1, 880)])
def test_a_segment_plays_the_audio_track_it_maps(media: Media, tmp_path: Path, track: int, hz: int) -> None:
    """The English track is a 440 Hz tone and the Russian one 880 Hz, so the pitch says which played."""
    segment = tmp_path / f"segment_{track}.ts"
    args = segment_args(
        "ffmpeg", [MediaInput(str(media.movie_mkv))], 6.0, 6.0, segment, has_video=True, audio_track=track
    )
    subprocess.run(args, check=True, capture_output=True)

    assert _tone_hz(segment) == pytest.approx(hz, rel=0.05)


# --- embedded subtitles (#100) ------------------------------------------------------


def _vtt_times(text: str) -> list[tuple[float, float, str]]:
    """A WebVTT file's cues, their tags left out: ASS bold comes through as ``<b>``."""

    def seconds(stamp: str) -> float:
        parts = [float(part) for part in stamp.split(":")]
        while len(parts) < 3:
            parts.insert(0, 0.0)
        return round(parts[0] * 3600 + parts[1] * 60 + parts[2], 3)

    cues = []
    for block in text.strip().split("\n\n")[1:]:
        lines = block.splitlines()
        timing = next(line for line in lines if " --> " in line)
        start, end = timing.split(" --> ")
        body = " ".join(lines[lines.index(timing) + 1 :])
        cues.append((seconds(start), seconds(end.split()[0]), re.sub(r"<[^>]+>", "", body)))
    return cues


def test_every_segment_s_cues_are_at_the_source_s_times(media: Media, tmp_path: Path) -> None:
    """Every cue a segment overlaps, at its own times, for all three tracks from one cut each.

    A cue across a seam comes out in both segments, with the same times.
    """
    tracks = {0: CUES, 1: CUES, 2: FORCED_CUES}
    for index in range(math.ceil(DURATION_S / SEGMENT_S)):
        start = index * SEGMENT_S
        length = min(SEGMENT_S, DURATION_S - start)
        outputs = [(track, tmp_path / f"subtitles_{index}.{track}.vtt") for track in tracks]
        args = subtitle_args("ffmpeg", MediaInput(str(media.movie_mkv)), float(start), float(length), outputs)
        subprocess.run([args[0], "-v", "error", *args[1:]], check=True, capture_output=True)

        for track, cues in tracks.items():
            expected = [(c.start, c.end, c.text) for c in cues if c.start < start + length and c.end > start]
            got = _vtt_times((tmp_path / f"subtitles_{index}.{track}.vtt").read_text())
            assert got == expected, (index, track)


def test_a_whole_track_comes_out_at_the_source_s_times(media: Media, tmp_path: Path) -> None:
    """The file starts at -0.023 s (AAC priming): without -copyts every cue would be 23 ms late."""
    whole = tmp_path / "whole.vtt"
    args = subtitle_file_args("ffmpeg", MediaInput(str(media.movie_mkv)), 1, whole)
    subprocess.run([args[0], "-v", "error", *args[1:]], check=True, capture_output=True)

    assert _vtt_times(whole.read_text()) == [(c.start, c.end, c.text) for c in CUES]


def test_movie_mp4_s_mov_text_cuts_the_same_way(media: Media, tmp_path: Path) -> None:
    out = tmp_path / "mp4.vtt"
    args = subtitle_args("ffmpeg", MediaInput(str(media.movie_mp4)), 6.0, 6.0, [(0, out)])
    subprocess.run([args[0], "-v", "error", *args[1:]], check=True, capture_output=True)

    assert _vtt_times(out.read_text()) == [(c.start, c.end, c.text) for c in CUES if c.start < 12 and c.end > 6]


# --- subtitle files beside the video (#101) -------------------------------------------


def test_the_sidecar_folder_s_files_match_and_convert_at_their_own_times(media: Media, tmp_path: Path) -> None:
    folder = media.sidecar_video.parent
    found = match_sidecars(media.sidecar_video.name, folder_listing(folder))

    assert [(sidecar.path, sidecar.language) for sidecar in found] == [
        ("Movie.en.srt", "en"),
        ("Subs/2_English.srt", "en"),
    ]
    for sidecar in found:
        out = tmp_path / f"{sidecar.title}.vtt"
        args = subtitle_file_args("ffmpeg", MediaInput(str(folder / sidecar.path)), 0, out)
        subprocess.run([args[0], "-v", "error", *args[1:]], check=True, capture_output=True)
        assert _vtt_times(out.read_text()) == [(c.start, c.end, c.text) for c in CUES]


def test_a_windows_1252_file_keeps_its_accents(tmp_path: Path) -> None:
    raw = "1\n00:00:01,000 --> 00:00:02,500\nCafé, señor\n".encode("cp1252")
    utf8 = tmp_path / "Movie.es.srt"
    utf8.write_text(decode_subtitles(raw), encoding="utf-8")
    out = tmp_path / "es.vtt"
    args = subtitle_file_args("ffmpeg", MediaInput(str(utf8)), 0, out)
    subprocess.run([args[0], "-v", "error", *args[1:]], check=True, capture_output=True)

    assert _vtt_times(out.read_text(encoding="utf-8")) == [(1.0, 2.5, "Café, señor")]
