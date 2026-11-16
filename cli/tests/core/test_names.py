import pytest
from src.core.names import clean_name


@pytest.mark.parametrize(
    ("raw", "expected"),
    [
        ("a.bin", "a.bin"),
        ("../../etc/x", "x"),
        ("/abs/y", "y"),
        ("..\\..\\win\\z.txt", "z.txt"),
        ("a\x00b\x1f.txt", "ab.txt"),
        ("...hidden", "hidden"),
        ("  spaced.txt", "spaced.txt"),
    ],
)
def test_clean_name_keeps_only_a_safe_basename(raw: str, expected: str) -> None:
    assert clean_name(raw) == expected


@pytest.mark.parametrize("raw", ["", "..", ".", "/", "   ", "\x00", "../"])
def test_clean_name_falls_back_when_nothing_is_left(raw: str) -> None:
    assert clean_name(raw) == "download"
    assert clean_name(raw, fallback="x") == "x"


def test_clean_name_limits_to_255_bytes_and_keeps_extension() -> None:
    name = clean_name("é" * 300 + ".mp4")
    assert len(name.encode()) <= 255
    assert name.endswith(".mp4")


def test_clean_name_leaves_room_for_partial_and_counter_suffixes() -> None:
    name = clean_name("é" * 300 + ".mp4")
    assert name.endswith(".mp4")
    longest = name[:-4] + " (999)" + ".mp4" + ".part.json.tmp"
    assert len(longest.encode()) <= 255


@pytest.mark.parametrize("raw", ["a.part", "a.part.json", "a.part.lock", "a.part.json.tmp"])
def test_clean_name_does_not_collide_with_partial_files(raw: str) -> None:
    assert not clean_name(raw).endswith((".part", ".part.json", ".part.lock", ".part.json.tmp"))


@pytest.mark.parametrize(
    ("raw", "expected"),
    [
        ("a\x85b\x9bc.txt", "abc.txt"),  # C1 controls (NEL, CSI)
        ("a\x80\x9f.txt", "a.txt"),
        ("movie‮gpj.exe", "moviegpj.exe"),  # right-to-left override
        ("a‪‫‬‭.txt", "a.txt"),
        ("x⁦y⁧z⁨w⁩.txt", "xyzw.txt"),  # isolates
        ("‮\x9b", "download"),
    ],
)
def test_clean_name_also_strips_c1_controls_and_bidi_overrides(raw: str, expected: str) -> None:
    assert clean_name(raw) == expected


def test_clean_name_keeps_ordinary_non_ascii_text() -> None:
    assert clean_name("Fête 映画 مرحبا.mkv") == "Fête 映画 مرحبا.mkv"
