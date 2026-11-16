import os
import stat
from pathlib import Path

import pytest
from src.core import cookies
from src.core.cookies import MAX_BYTES, check_cookies, copy_private
from src.core.errors import UsageError

SECRET = "s3cr3t-session-value-7f3a"
HEADER = "# Netscape HTTP Cookie File\n"
LINE = f".example.com\tTRUE\t/\tFALSE\t2000000000\tsid\t{SECRET}\n"


def write(path: Path, text: str, mode: int = 0o600) -> Path:
    path.write_text(text)
    path.chmod(mode)
    return path


def test_a_valid_file_gives_its_absolute_path_and_no_warning(tmp_path: Path) -> None:
    f = write(tmp_path / "c.txt", HEADER + LINE)
    checked = check_cookies(f)
    assert checked.path == f.resolve()
    assert checked.warning is None


@pytest.mark.parametrize("header", ["# Netscape HTTP Cookie File", "# HTTP Cookie File"])
def test_both_header_lines_are_accepted_after_blank_lines(tmp_path: Path, header: str) -> None:
    f = write(tmp_path / "c.txt", f"\n\n{header}\n")
    assert check_cookies(f).path == f.resolve()


def test_a_file_without_header_but_with_a_seven_field_line_is_accepted(tmp_path: Path) -> None:
    f = write(tmp_path / "c.txt", LINE)
    assert check_cookies(f).path == f.resolve()


def test_a_file_that_is_not_netscape_format_is_refused(tmp_path: Path) -> None:
    f = write(tmp_path / "c.txt", f'{{"name": "sid", "value": "{SECRET}"}}\n')
    with pytest.raises(UsageError, match="not a Netscape cookies file") as e:
        check_cookies(f)
    assert SECRET not in str(e.value)
    assert str(f.resolve()) in str(e.value)


def test_a_line_with_the_wrong_number_of_fields_is_not_netscape(tmp_path: Path) -> None:
    f = write(tmp_path / "c.txt", f".example.com\tTRUE\t/\tFALSE\t0\tsid-{SECRET}\n")
    with pytest.raises(UsageError, match="not a Netscape cookies file"):
        check_cookies(f)


def test_a_bad_line_among_good_ones_refuses_the_file_without_its_content(tmp_path: Path) -> None:
    f = write(tmp_path / "c.txt", HEADER + LINE + f"garbage-{SECRET}\n" + LINE)
    with pytest.raises(UsageError, match="not a Netscape cookies file") as e:
        check_cookies(f)
    assert SECRET not in str(e.value)


def test_a_line_with_a_non_numeric_expiry_is_refused(tmp_path: Path) -> None:
    f = write(tmp_path / "c.txt", HEADER + f".example.com\tTRUE\t/\tFALSE\tsoon-{SECRET}\tsid\tv\n")
    with pytest.raises(UsageError, match="not a Netscape cookies file") as e:
        check_cookies(f)
    assert SECRET not in str(e.value)


@pytest.mark.parametrize("expiry", ["", "0", "2000000000", "2000000000.5"])
def test_an_empty_or_numeric_expiry_is_accepted(tmp_path: Path, expiry: str) -> None:
    f = write(tmp_path / "c.txt", HEADER + f".example.com\tTRUE\t/\tFALSE\t{expiry}\tsid\tv\n")
    assert check_cookies(f).path == f.resolve()


def test_an_httponly_prefixed_line_is_checked_like_yt_dlp_does(tmp_path: Path) -> None:
    good = write(tmp_path / "good.txt", HEADER + "#HttpOnly_" + LINE)
    assert check_cookies(good).path == good.resolve()
    bad = write(tmp_path / "bad.txt", HEADER + f"#HttpOnly_x\t{SECRET}\n")
    with pytest.raises(UsageError, match="not a Netscape cookies file") as e:
        check_cookies(bad)
    assert SECRET not in str(e.value)


def test_comments_and_blank_lines_are_fine(tmp_path: Path) -> None:
    f = write(tmp_path / "c.txt", HEADER + "# a comment\n\n   \n" + LINE)
    assert check_cookies(f).path == f.resolve()


def test_a_byte_order_mark_before_the_header_is_accepted(tmp_path: Path) -> None:
    f = tmp_path / "c.txt"
    f.write_bytes(b"\xef\xbb\xbf" + HEADER.encode())
    f.chmod(0o600)
    assert check_cookies(f).path == f.resolve()


def test_bytes_that_are_not_utf_8_are_refused(tmp_path: Path) -> None:
    f = tmp_path / "c.txt"
    f.write_bytes(HEADER.encode() + b"\xff\xfe\n")
    f.chmod(0o600)
    with pytest.raises(UsageError, match="not a Netscape cookies file"):
        check_cookies(f)


def test_a_path_in_a_message_has_no_control_characters(tmp_path: Path) -> None:
    with pytest.raises(UsageError, match="not found") as e:
        check_cookies(tmp_path / "evil\x1b[31m.txt")
    assert "\x1b" not in str(e.value)


def test_a_missing_file_is_refused_and_named(tmp_path: Path) -> None:
    with pytest.raises(UsageError, match="not found") as e:
        check_cookies(tmp_path / "missing.txt")
    assert "missing.txt" in str(e.value)
    assert e.value.exit_code == 2


def test_a_directory_is_refused(tmp_path: Path) -> None:
    with pytest.raises(UsageError, match="not a regular file"):
        check_cookies(tmp_path)


@pytest.mark.skipif(os.getuid() == 0, reason="root reads everything")
def test_an_unreadable_file_is_refused(tmp_path: Path) -> None:
    f = write(tmp_path / "c.txt", HEADER + LINE, mode=0o000)
    with pytest.raises(UsageError, match="cannot read"):
        check_cookies(f)


def test_a_file_over_10_mb_is_refused(tmp_path: Path) -> None:
    f = tmp_path / "big.txt"
    f.write_text(HEADER)
    os.truncate(f, MAX_BYTES + 1)
    with pytest.raises(UsageError, match="too big"):
        check_cookies(f)


def test_a_file_of_exactly_10_mb_is_accepted(tmp_path: Path) -> None:
    f = tmp_path / "ok.txt"
    f.write_text(HEADER + "#" * (MAX_BYTES - len(HEADER) - 1) + "\n")  # NUL padding would be bad lines
    assert f.stat().st_size == MAX_BYTES
    assert check_cookies(f).path == f.resolve()


def test_a_file_others_can_read_gives_a_warning_that_names_the_path_only(tmp_path: Path) -> None:
    f = write(tmp_path / "c.txt", HEADER + LINE, mode=0o644)
    warning = check_cookies(f).warning
    assert warning is not None
    assert str(f.resolve()) in warning
    assert "chmod 600" in warning
    assert SECRET not in warning


def test_a_group_readable_file_also_warns(tmp_path: Path) -> None:
    assert check_cookies(write(tmp_path / "c.txt", HEADER, mode=0o640)).warning is not None


def test_a_symlink_is_resolved_to_its_target(tmp_path: Path) -> None:
    target = write(tmp_path / "real.txt", HEADER + LINE)
    link = tmp_path / "link.txt"
    link.symlink_to(target)
    assert check_cookies(link).path == target.resolve()


def test_a_symlink_to_a_bad_file_is_refused(tmp_path: Path) -> None:
    target = write(tmp_path / "real.txt", "nope\n")
    link = tmp_path / "link.txt"
    link.symlink_to(target)
    with pytest.raises(UsageError, match="not a Netscape cookies file"):
        check_cookies(link)


def test_home_is_expanded(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("HOME", str(tmp_path))
    f = write(tmp_path / "c.txt", HEADER)
    assert check_cookies(Path("~/c.txt")).path == f.resolve()


def test_copy_private_makes_a_0600_copy_with_the_same_bytes(tmp_path: Path) -> None:
    src = write(tmp_path / "c.txt", HEADER + LINE, mode=0o644)
    copy = copy_private(src, tmp_path)
    assert copy != src
    assert copy.parent == tmp_path
    assert copy.name.startswith("anydm-cookies-") and copy.name.endswith(".txt")
    assert copy.read_bytes() == src.read_bytes()
    assert stat.S_IMODE(copy.stat().st_mode) == 0o600


def test_copy_private_drops_a_byte_order_mark_so_yt_dlp_reads_the_header(tmp_path: Path) -> None:
    src = tmp_path / "c.txt"
    src.write_bytes(b"\xef\xbb\xbf" + HEADER.encode())
    assert copy_private(src, tmp_path).read_bytes() == HEADER.encode()


def test_copy_private_uses_the_temp_folder_by_default(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr("tempfile.tempdir", str(tmp_path))
    src = write(tmp_path / "c.txt", HEADER)
    assert copy_private(src).parent == tmp_path


def test_copy_private_leaves_nothing_behind_when_the_source_cannot_be_read(tmp_path: Path) -> None:
    with pytest.raises(OSError):
        copy_private(tmp_path / "missing.txt", tmp_path)
    assert list(tmp_path.glob("anydm-cookies-*")) == []


def test_the_login_hints_point_at_the_flag_and_at_expiry() -> None:
    assert "--cookies" in cookies.LOGIN_HINT_NO_COOKIES
    assert "expired" in cookies.LOGIN_HINT_EXPIRED
