# WEB_URL = "https://books.toscrape.com"
# WEB_URL = "https://www.thedailystar.net"
from pydantic import HttpUrl

from src.core.type import Code, ErrorType

WEB_URL: HttpUrl = HttpUrl("https://www.wikipedia.org")
# PW_USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36"
PW_USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
    "AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/91.0.4472.124 Safari/537.36"
)

EXCEPTION_CODE_MAP: dict[type[Exception], Code] = {
    ValueError: Code.BAD_REQUEST,
    KeyError: Code.NOT_FOUND,
    TypeError: Code.UNPROCESSABLE_ENTITY,
    FileNotFoundError: Code.NOT_FOUND,
    NotImplementedError: Code.NOT_IMPLEMENTED,
}

EXCEPTION_ERROR_TYPE_MAP: dict[type[Exception], ErrorType] = {
    ValueError: ErrorType.INVALID_REQUEST,
    KeyError: ErrorType.DOES_NOT_EXIST,
    TypeError: ErrorType.TYPE_ERROR,
    FileNotFoundError: ErrorType.FILE_NOT_FOUND,
    NotImplementedError: ErrorType.NOT_IMPLEMENTED,
}

