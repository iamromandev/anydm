from urllib.parse import ParseResult, urlparse

from tortoise.exceptions import ValidationError
from tortoise.validators import Validator


class UrlValidator(Validator):
    def __call__(self, value: str | None) -> None:
        if value:
            try:
                result: ParseResult = urlparse(value)
                if all([result.scheme, result.netloc]):
                    return None
            except Exception:
                pass
        raise ValidationError(f"'{value}' is not a valid url.")
