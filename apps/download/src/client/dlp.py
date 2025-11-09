from functools import cached_property
from typing import Annotated

from pydantic import Field

from src.core.factory import SingletonMeta


class DlpClient(metaclass=SingletonMeta):
    _initialized: Annotated[bool, Field(default=False)] = False

    def __init__(self) -> None:
        if self._initialized:
            return
        self._initialized = True

    @cached_property
    def _tag(self) -> str:
        return self.__class__.__name__
