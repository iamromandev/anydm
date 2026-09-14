import threading
from typing import Any, ClassVar, TypeVar, cast

_T = TypeVar("_T")


class SingletonMeta(type):
    _instances: ClassVar[dict[type, Any]] = {}
    _lock: ClassVar[threading.Lock] = threading.Lock()

    def __call__(cls: type[_T], *args: Any, **kwargs: Any) -> _T:
        meta = cast(type[SingletonMeta], cls)
        with meta._lock:
            if cls not in meta._instances:
                meta._instances[cls] = type.__call__(cls, *args, **kwargs)
        return cast(_T, meta._instances[cls])
