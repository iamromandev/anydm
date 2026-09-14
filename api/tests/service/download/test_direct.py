import pytest
from src.service.download.direct import filename_from_url


@pytest.mark.parametrize(
    ("url", "expected"),
    [
        ("https://cdn.test/files/report.pdf", "report.pdf"),
        ("https://cdn.test/files/report.pdf?token=abc", "report.pdf"),
        ("https://cdn.test/a/b/c/archive.tar.gz", "archive.tar.gz"),
        ("https://cdn.test/files/my%20file.zip", "my_file.zip"),
        ("https://cdn.test/", "download"),
        ("https://cdn.test", "download"),
        ("https://cdn.test/files/", "download"),
        ("https://cdn.test/../../etc/passwd", "passwd"),
    ],
)
def test_filename_from_url(url: str, expected: str) -> None:
    assert filename_from_url(url) == expected
