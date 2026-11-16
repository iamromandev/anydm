from pathlib import Path


class AnydmError(Exception):
    exit_code = 1


class UsageError(AnydmError):
    exit_code = 2


class DownloadError(AnydmError):
    exit_code = 1


class PartialDownload(DownloadError):
    """Some entries of a playlist failed; `paths` are the ones that succeeded."""

    def __init__(self, message: str, paths: list[Path]) -> None:
        super().__init__(message)
        self.paths = paths
