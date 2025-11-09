import threading
from typing import Any, TypeVar

_T = TypeVar("_T")


class SingletonMeta(type):
    _instances: dict[type, Any] = {}
    _lock: threading.Lock = threading.Lock()

    def __call__(cls: type[_T], *args: Any, **kwargs: Any) -> _T:
        with cls._lock:
            if cls not in cls._instances:
                cls._instances[cls] = super().__call__(*args, **kwargs)
        return cls._instances[cls]