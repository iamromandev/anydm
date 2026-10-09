import uuid

from src.core.error import Error
from src.core.type import Code, ErrorType


def _unprocessable(message: str) -> Error:
    return Error.create(code=Code.UNPROCESSABLE_ENTITY, message=message, error_type=ErrorType.UNPROCESSABLE_ENTITY)


def not_found(id: uuid.UUID) -> Error:
    return Error.create(code=Code.NOT_FOUND, message=f"There is no category with id {id}", error_type=ErrorType.NOT_FOUND)


def unknown(id: uuid.UUID) -> Error:
    """An add or a move naming a category that isn't there: the request, not the URL, is wrong."""
    return _unprocessable(f"There is no category with id {id}")


def name_taken(name: str) -> Error:
    return Error.conflict(message=f"There is already a category named {name}")


def name_missing() -> Error:
    return _unprocessable("A category needs a name")


def name_meaningless() -> Error:
    return _unprocessable("A category name needs a letter or a digit")


def builtin() -> Error:
    return _unprocessable("Downloads is built in: its folder can't change and it can't be deleted")


def in_use(name: str, count: int) -> Error:
    held = "1 download" if count == 1 else f"{count} downloads"
    return Error.conflict(message=f"{name} still holds {held}; move them first")


def bad_order() -> Error:
    return _unprocessable("List every category exactly once")


def running(status: str) -> Error:
    return Error.conflict(message=f"Download is {status}; move it once it stops or finishes")


def torrent() -> Error:
    return _unprocessable("A torrent stays in the category it was added to")


def collection_video() -> Error:
    return _unprocessable("Move the collection this video belongs to")


def collection_running() -> Error:
    return Error.conflict(message="A video in this collection is downloading; move it once that finishes")
