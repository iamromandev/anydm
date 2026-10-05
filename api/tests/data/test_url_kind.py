"""The URL schemes a transfer can be asked to handle."""

from src.data.type import UrlKind


def test_url_kind_is_a_protocol() -> None:
    assert (UrlKind.HTTP, UrlKind.HTTPS, UrlKind.MAGNET) == ("http", "https", "magnet")
    assert (UrlKind.FTP, UrlKind.FTPS, UrlKind.SFTP, UrlKind.FILE) == ("ftp", "ftps", "sftp", "file")
