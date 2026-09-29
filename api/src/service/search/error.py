from src.core.error import Error, ErrorDetail
from src.core.type import Code, ErrorType
from src.data.schema.search import IndexerErrorSchema


def disabled() -> Error:
    return Error.create(
        code=Code.NOT_FOUND,
        message="Search is off: no indexers are configured (SEARCH_INDEXERS)",
        error_type=ErrorType.SEARCH_DISABLED,
    )


def failed(errors: list[IndexerErrorSchema]) -> Error:
    return Error.create(
        code=Code.BAD_GATEWAY,
        message="No indexer answered the search",
        error_type=ErrorType.SEARCH_FAILED,
        details=[ErrorDetail(subject=e.indexer, description=e.message) for e in errors],
        retry_able=True,
    )


def link_not_from_indexer() -> Error:
    return Error.create(
        code=Code.BAD_REQUEST,
        message="That link isn't from a configured indexer",
        error_type=ErrorType.LINK_NOT_FROM_INDEXER,
    )


def fetch_failed(reason: str) -> Error:
    return Error.create(
        code=Code.BAD_GATEWAY,
        message=f"Couldn't fetch the torrent: {reason}",
        error_type=ErrorType.BAD_GATEWAY,
        retry_able=True,
    )


def too_large(limit_bytes: int) -> Error:
    return Error.create(
        code=Code.REQUEST_ENTITY_TOO_LARGE,
        message=f"The torrent file is over {limit_bytes // (1024 * 1024)} MB",
        error_type=ErrorType.FILE_TOO_LARGE,
    )


def not_a_torrent() -> Error:
    return Error.create(
        code=Code.UNPROCESSABLE_ENTITY,
        message="The indexer didn't answer with a torrent file",
        error_type=ErrorType.NOT_A_TORRENT,
    )
