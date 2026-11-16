from pathlib import Path

from src.engine.http import resume


def test_record_file_is_private(tmp_path: Path) -> None:
    final = tmp_path / "a.bin"
    resume.write(final, resume.Record("http://u:p@h/x", "http://h/y?token=1", 1, None, None, []))
    assert resume.record_path(final).stat().st_mode & 0o777 == 0o600
