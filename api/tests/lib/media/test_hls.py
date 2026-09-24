"""Reading HLS media playlists, and cutting one down to the fragments a segment needs (#87)."""

import pytest
from src.lib.media.hls import ByteRange, InitSection, PlaylistRefused, parse_media_playlist, sub_playlist

URL = "https://cdn.test/video/720p/index.m3u8"
BASE = "https://cdn.test/video/720p/"


def _playlist(*body: str, end: bool = True) -> str:
    lines = ["#EXTM3U", "#EXT-X-TARGETDURATION:4", *body]
    if end:
        lines.append("#EXT-X-ENDLIST")
    return "\n".join(lines) + "\n"


def _fragments(count: int, seconds: float = 2.0, extension: str = "ts") -> list[str]:
    body: list[str] = []
    for n in range(count):
        body += [f"#EXTINF:{seconds:.3f},", f"frag{n}.{extension}"]
    return body


def _cut(text: str, start: float, end: float) -> tuple[list[str], float]:
    cut, first = sub_playlist(parse_media_playlist(URL, text), start, end)
    return cut.splitlines(), first


def _uris(lines: list[str]) -> list[str]:
    return [line for line in lines if not line.startswith("#")]


# --- reading ------------------------------------------------------------------


def test_fragments_follow_one_another_from_zero() -> None:
    playlist = parse_media_playlist(URL, _playlist(*_fragments(3)))

    assert [(f.start, f.duration) for f in playlist.fragments] == [(0.0, 2.0), (2.0, 2.0), (4.0, 2.0)]
    assert [f.sequence for f in playlist.fragments] == [0, 1, 2]
    assert playlist.duration == 6.0
    assert playlist.version == 3
    assert all(f.init is None and f.key is None and f.byterange is None for f in playlist.fragments)


def test_uris_resolve_against_the_url_the_playlist_came_from() -> None:
    # The URL after any redirect: a CDN often answers from somewhere else.
    text = _playlist(
        "#EXTINF:2,", "frag0.ts", "#EXTINF:2,", "/root/frag1.ts", "#EXTINF:2,", "https://other.test/frag2.ts"
    )

    assert [f.url for f in parse_media_playlist(URL, text).fragments] == [
        f"{BASE}frag0.ts",
        "https://cdn.test/root/frag1.ts",
        "https://other.test/frag2.ts",
    ]


def test_the_media_sequence_numbers_the_fragments() -> None:
    playlist = parse_media_playlist(URL, _playlist("#EXT-X-VERSION:6", "#EXT-X-MEDIA-SEQUENCE:40", *_fragments(2)))

    assert [f.sequence for f in playlist.fragments] == [40, 41]
    assert playlist.version == 6


def test_an_fmp4_fragment_carries_the_init_section_in_effect() -> None:
    text = _playlist('#EXT-X-MAP:URI="init.mp4"', *_fragments(2, 3.0, "m4s"))

    init = InitSection(f"{BASE}init.mp4", None)
    assert [f.init for f in parse_media_playlist(URL, text).fragments] == [init, init]


def test_an_init_section_can_be_a_range_of_a_file() -> None:
    text = _playlist(
        '#EXT-X-MAP:URI="main.mp4",BYTERANGE="720@0"', "#EXT-X-BYTERANGE:1000@720", "#EXTINF:2,", "main.mp4",
        '#EXT-X-MAP:URI="other.mp4",BYTERANGE="512"', "#EXT-X-BYTERANGE:900@512", "#EXTINF:2,", "other.mp4",
    )
    first, second = parse_media_playlist(URL, text).fragments

    assert first.init == InitSection(f"{BASE}main.mp4", ByteRange(720, 0))
    assert first.byterange == ByteRange(1000, 720)
    # A MAP's range with no offset starts at the top of its file.
    assert second.init == InitSection(f"{BASE}other.mp4", ByteRange(512, 0))


def test_a_byte_range_with_no_offset_follows_on_from_the_last_one() -> None:
    text = _playlist(
        "#EXTINF:2,", "#EXT-X-BYTERANGE:1000@0", "all.ts",
        "#EXTINF:2,", "#EXT-X-BYTERANGE:1500", "all.ts",
        "#EXTINF:2,", "#EXT-X-BYTERANGE:800", "all.ts",
    )

    assert [f.byterange for f in parse_media_playlist(URL, text).fragments] == [
        ByteRange(1000, 0),
        ByteRange(1500, 1000),
        ByteRange(800, 2500),
    ]


def test_aes_128_is_kept_with_its_key_uri_made_absolute() -> None:
    text = _playlist(
        '#EXT-X-KEY:METHOD=AES-128,URI="keys/k1",IV=0x0123456789abcdef0123456789abcdef', "#EXTINF:2,", "a.ts",
        '#EXT-X-KEY:METHOD=AES-128,URI="https://keys.test/k2"', "#EXTINF:2,", "b.ts",
    )
    first, second = parse_media_playlist(URL, text).fragments

    assert first.key == f'#EXT-X-KEY:METHOD=AES-128,URI="{BASE}keys/k1",IV=0x0123456789abcdef0123456789abcdef'
    # No IV: ffmpeg decrypts with the fragment's sequence number.
    assert second.key == '#EXT-X-KEY:METHOD=AES-128,URI="https://keys.test/k2"'


def test_method_none_ends_the_encryption() -> None:
    text = _playlist(
        '#EXT-X-KEY:METHOD=AES-128,URI="k"', "#EXTINF:2,", "a.ts", "#EXT-X-KEY:METHOD=NONE", "#EXTINF:2,", "b.ts"
    )

    assert [f.key is None for f in parse_media_playlist(URL, text).fragments] == [False, True]


def test_a_discontinuity_marks_the_fragment_after_it() -> None:
    text = _playlist("#EXTINF:2,", "a.ts", "#EXT-X-DISCONTINUITY", "#EXTINF:2,", "b.ts", "#EXTINF:2,", "c.ts")

    assert [f.discontinuity for f in parse_media_playlist(URL, text).fragments] == [False, True, False]


@pytest.mark.parametrize(
    ("text", "live"),
    [
        (_playlist("#EXT-X-STREAM-INF:BANDWIDTH=1000000", "720p/index.m3u8"), False),
        (_playlist(), False),
        ("<html><body>Not Found</body></html>", False),
        (_playlist('#EXT-X-KEY:METHOD=SAMPLE-AES,URI="k"', "#EXTINF:2,", "a.ts"), False),
        (_playlist("#EXTINF:two,", "a.ts"), False),
        (_playlist("#EXTINF:2,", "a.ts", end=False), True),
    ],
    ids=["master", "empty", "not-a-playlist", "sample-aes", "unreadable", "live"],
)
def test_a_playlist_the_player_cannot_cut_is_refused(text: str, live: bool) -> None:
    with pytest.raises(PlaylistRefused) as caught:
        parse_media_playlist(URL, text)

    assert caught.value.live is live


# --- cutting ------------------------------------------------------------------


def test_a_cut_is_the_fragments_its_window_overlaps() -> None:
    # frag2 ends at 6 and frag6 starts at 12: touching the window isn't overlapping it.
    lines, first = _cut(_playlist(*_fragments(10)), 6.0, 12.0)

    assert first == 6.0
    assert lines == [
        "#EXTM3U",
        "#EXT-X-VERSION:3",
        "#EXT-X-TARGETDURATION:2",
        "#EXT-X-MEDIA-SEQUENCE:3",
        "#EXT-X-PLAYLIST-TYPE:VOD",
        "#EXTINF:2.000000,",
        f"{BASE}frag3.ts",
        "#EXTINF:2.000000,",
        f"{BASE}frag4.ts",
        "#EXTINF:2.000000,",
        f"{BASE}frag5.ts",
        "#EXT-X-ENDLIST",
    ]


def test_a_cut_takes_the_fragments_across_its_edges_whole() -> None:
    lines, first = _cut(_playlist(*_fragments(5, 4.0)), 6.0, 12.0)

    assert first == 4.0
    assert _uris(lines) == [f"{BASE}frag1.ts", f"{BASE}frag2.ts"]


def test_one_fragment_longer_than_the_window_is_the_whole_cut() -> None:
    lines, first = _cut(_playlist(*_fragments(3, 10.0)), 12.0, 18.0)

    assert first == 10.0
    assert _uris(lines) == [f"{BASE}frag1.ts"]


def test_a_window_past_the_end_gets_the_last_fragment() -> None:
    # The final segment's window can overshoot by a rounding difference.
    lines, first = _cut(_playlist(*_fragments(3)), 6.0, 12.0)

    assert first == 4.0
    assert _uris(lines) == [f"{BASE}frag2.ts"]


def test_the_target_duration_is_the_longest_fragment_rounded_up() -> None:
    lines, _ = _cut(_playlist("#EXTINF:2.5,", "a.ts", "#EXTINF:3.2,", "b.ts"), 0.0, 6.0)

    assert "#EXT-X-TARGETDURATION:4" in lines


def test_a_cut_from_the_middle_keeps_the_init_section_and_key_in_effect() -> None:
    text = _playlist(
        "#EXT-X-VERSION:7",
        '#EXT-X-MAP:URI="init.mp4"',
        '#EXT-X-KEY:METHOD=AES-128,URI="k"',
        *_fragments(6, 2.0, "m4s"),
    )
    lines, _ = _cut(text, 4.0, 8.0)

    assert lines[1] == "#EXT-X-VERSION:7"
    # Kept, so AES-128 without an IV still decrypts each fragment with its own number.
    assert lines[3] == "#EXT-X-MEDIA-SEQUENCE:2"
    assert lines[5:9] == [
        f'#EXT-X-MAP:URI="{BASE}init.mp4"',
        f'#EXT-X-KEY:METHOD=AES-128,URI="{BASE}k"',
        "#EXTINF:2.000000,",
        f"{BASE}frag2.m4s",
    ]


def test_a_cut_repeats_whatever_changes_inside_it() -> None:
    text = _playlist(
        '#EXT-X-MAP:URI="a.mp4"',
        '#EXT-X-KEY:METHOD=AES-128,URI="k"',
        "#EXTINF:2,", "a0.m4s",
        "#EXTINF:2,", "a1.m4s",
        "#EXT-X-DISCONTINUITY",
        '#EXT-X-MAP:URI="b.mp4"',
        "#EXTINF:2,", "b0.m4s",
        "#EXT-X-KEY:METHOD=NONE",
        "#EXTINF:2,", "b1.m4s",
    )
    lines, _ = _cut(text, 2.0, 8.0)

    assert lines[5:] == [
        f'#EXT-X-MAP:URI="{BASE}a.mp4"',
        f'#EXT-X-KEY:METHOD=AES-128,URI="{BASE}k"',
        "#EXTINF:2.000000,",
        f"{BASE}a1.m4s",
        "#EXT-X-DISCONTINUITY",
        f'#EXT-X-MAP:URI="{BASE}b.mp4"',
        "#EXTINF:2.000000,",
        f"{BASE}b0.m4s",
        "#EXT-X-KEY:METHOD=NONE",
        "#EXTINF:2.000000,",
        f"{BASE}b1.m4s",
        "#EXT-X-ENDLIST",
    ]


def test_a_cut_never_opens_on_a_discontinuity() -> None:
    lines, _ = _cut(_playlist("#EXTINF:2,", "a.ts", "#EXT-X-DISCONTINUITY", "#EXTINF:2,", "b.ts"), 2.0, 4.0)

    assert "#EXT-X-DISCONTINUITY" not in lines


def test_a_cut_writes_every_byte_range_with_its_offset() -> None:
    text = _playlist("#EXTINF:2,", "#EXT-X-BYTERANGE:1000@0", "all.ts", "#EXTINF:2,", "#EXT-X-BYTERANGE:1500", "all.ts")
    lines, _ = _cut(text, 2.0, 4.0)

    assert lines[5:8] == ["#EXT-X-BYTERANGE:1500@1000", "#EXTINF:2.000000,", f"{BASE}all.ts"]


def test_a_cut_reads_back_as_the_fragments_it_took() -> None:
    source = parse_media_playlist(
        URL,
        _playlist(
            "#EXT-X-VERSION:7",
            "#EXT-X-MEDIA-SEQUENCE:5",
            '#EXT-X-MAP:URI="main.mp4",BYTERANGE="700@0"',
            '#EXT-X-KEY:METHOD=AES-128,URI="k"',
            "#EXT-X-BYTERANGE:1000@700", "#EXTINF:2,", "main.mp4",
            "#EXT-X-BYTERANGE:1000", "#EXTINF:2,", "main.mp4",
            "#EXT-X-DISCONTINUITY",
            "#EXT-X-KEY:METHOD=NONE",
            "#EXT-X-BYTERANGE:1000", "#EXTINF:2,", "main.mp4",
            "#EXT-X-BYTERANGE:1000", "#EXTINF:2,", "main.mp4",
        ),
    )
    text, first = sub_playlist(source, 2.0, 7.0)
    taken = source.fragments[1:4]

    again = parse_media_playlist("https://anywhere.test/cut.m3u8", text)

    assert first == 2.0
    assert again.version == 7
    assert [(f.url, f.duration, f.sequence, f.byterange, f.init, f.key, f.discontinuity) for f in again.fragments] == [
        (f.url, f.duration, f.sequence, f.byterange, f.init, f.key, f.discontinuity) for f in taken
    ]
