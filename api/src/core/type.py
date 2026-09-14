"""Shared enums: string enums (``Status``, ``ErrorType``), ``Code`` (HTTP ints)."""

from enum import IntEnum, StrEnum

from starlette import status


def _http_status(*attr_names: str) -> int:
    for name in attr_names:
        if hasattr(status, name):
            return int(getattr(status, name))
    msg = f"starlette.status has none of: {', '.join(attr_names)}"
    raise AttributeError(msg)


class Status(StrEnum):
    SUCCESS = "success"
    ERROR = "error"


class ErrorType(StrEnum):
    # General
    UNKNOWN_ERROR = "unknown_error"
    SERVER_ERROR = "server_error"
    SERVICE_UNAVAILABLE = "service_unavailable"
    NOT_IMPLEMENTED = "not_implemented"
    TIMEOUT = "timeout"
    BAD_GATEWAY = "bad_gateway"
    DEPENDENCY_FAILURE = "dependency_failure"
    GATEWAY_TIMEOUT = "gateway_timeout"

    # HTTP-related
    VALIDATION_ERROR = "validation_error"
    INVALID_REQUEST = "invalid_request"
    UNPROCESSABLE_ENTITY = "unprocessable_entity"
    RATE_LIMITED = "rate_limited"
    NOT_FOUND = "not_found"
    METHOD_NOT_ALLOWED = "method_not_allowed"
    BAD_REQUEST = "bad_request"
    UNSUPPORTED_MEDIA_TYPE = "unsupported_media_type"
    TOO_MANY_REQUESTS = "too_many_requests"
    FORBIDDEN = "forbidden"
    UNAUTHORIZED = "unauthorized"
    CONFLICT = "conflict"
    PAYLOAD_TOO_LARGE = "payload_too_large"

    # Authentication & Authorization
    AUTHENTICATION_ERROR = "authentication_error"
    AUTHORIZATION_ERROR = "authorization_error"
    TOKEN_EXPIRED = "token_expired"
    TOKEN_INVALID = "token_invalid"
    SESSION_EXPIRED = "session_expired"

    # Business logic / domain errors
    DUPLICATE_ENTRY = "duplicate_entry"
    ALREADY_EXISTS = "already_exists"
    DOES_NOT_EXIST = "does_not_exist"
    INVALID_STATE = "invalid_state"
    PRECONDITION_FAILED = "precondition_failed"
    RESOURCE_LOCKED = "resource_locked"

    # Input/Output & Type Issues
    TYPE_ERROR = "type_error"
    VALUE_ERROR = "value_error"
    MISSING_FIELD = "missing_field"
    INVALID_FIELD = "invalid_field"
    INVALID_FORMAT = "invalid_format"
    UNSUPPORTED_OPERATION = "unsupported_operation"

    # External/3rd-party issues
    EXTERNAL_API_ERROR = "external_api_error"
    THIRD_PARTY_ERROR = "third_party_error"
    INTEGRATION_FAILURE = "integration_failure"

    # Database/Storage
    DB_ERROR = "db_error"
    DATA_INTEGRITY_ERROR = "data_integrity_error"
    UNIQUE_CONSTRAINT_VIOLATION = "unique_constraint_violation"
    FOREIGN_KEY_VIOLATION = "foreign_key_violation"

    # File Handling
    FILE_NOT_FOUND = "file_not_found"
    FILE_UPLOAD_ERROR = "file_upload_error"
    FILE_FORMAT_ERROR = "file_format_error"
    FILE_TOO_LARGE = "file_too_large"

    # Email / Notifications
    EMAIL_SEND_FAILED = "email_send_failed"
    NOTIFICATION_ERROR = "notification_error"


class Code(IntEnum):
    # 1xx Informational
    CONTINUE = status.HTTP_100_CONTINUE
    SWITCHING_PROTOCOLS = status.HTTP_101_SWITCHING_PROTOCOLS
    PROCESSING = status.HTTP_102_PROCESSING
    EARLY_HINTS = status.HTTP_103_EARLY_HINTS

    # 2xx Success
    OK = status.HTTP_200_OK
    CREATED = status.HTTP_201_CREATED
    ACCEPTED = status.HTTP_202_ACCEPTED
    NON_AUTHORITATIVE_INFORMATION = status.HTTP_203_NON_AUTHORITATIVE_INFORMATION
    NO_CONTENT = status.HTTP_204_NO_CONTENT
    RESET_CONTENT = status.HTTP_205_RESET_CONTENT
    PARTIAL_CONTENT = status.HTTP_206_PARTIAL_CONTENT
    MULTI_STATUS = status.HTTP_207_MULTI_STATUS
    ALREADY_REPORTED = status.HTTP_208_ALREADY_REPORTED
    IM_USED = status.HTTP_226_IM_USED

    # 3xx Redirection
    MULTIPLE_CHOICES = status.HTTP_300_MULTIPLE_CHOICES
    MOVED_PERMANENTLY = status.HTTP_301_MOVED_PERMANENTLY
    FOUND = status.HTTP_302_FOUND
    SEE_OTHER = status.HTTP_303_SEE_OTHER
    NOT_MODIFIED = status.HTTP_304_NOT_MODIFIED
    USE_PROXY = status.HTTP_305_USE_PROXY
    TEMPORARY_REDIRECT = status.HTTP_307_TEMPORARY_REDIRECT
    PERMANENT_REDIRECT = status.HTTP_308_PERMANENT_REDIRECT

    # 4xx Client Errors
    BAD_REQUEST = status.HTTP_400_BAD_REQUEST
    UNAUTHORIZED = status.HTTP_401_UNAUTHORIZED
    PAYMENT_REQUIRED = status.HTTP_402_PAYMENT_REQUIRED
    FORBIDDEN = status.HTTP_403_FORBIDDEN
    NOT_FOUND = status.HTTP_404_NOT_FOUND
    METHOD_NOT_ALLOWED = status.HTTP_405_METHOD_NOT_ALLOWED
    NOT_ACCEPTABLE = status.HTTP_406_NOT_ACCEPTABLE
    PROXY_AUTHENTICATION_REQUIRED = status.HTTP_407_PROXY_AUTHENTICATION_REQUIRED
    REQUEST_TIMEOUT = status.HTTP_408_REQUEST_TIMEOUT
    CONFLICT = status.HTTP_409_CONFLICT
    GONE = status.HTTP_410_GONE
    LENGTH_REQUIRED = status.HTTP_411_LENGTH_REQUIRED
    PRECONDITION_FAILED = status.HTTP_412_PRECONDITION_FAILED
    REQUEST_ENTITY_TOO_LARGE = _http_status(
        "HTTP_413_CONTENT_TOO_LARGE",
        "HTTP_413_REQUEST_ENTITY_TOO_LARGE",
    )
    REQUEST_URI_TOO_LONG = _http_status(
        "HTTP_414_URI_TOO_LONG",
        "HTTP_414_REQUEST_URI_TOO_LONG",
    )
    UNSUPPORTED_MEDIA_TYPE = status.HTTP_415_UNSUPPORTED_MEDIA_TYPE
    REQUESTED_RANGE_NOT_SATISFIABLE = _http_status(
        "HTTP_416_RANGE_NOT_SATISFIABLE",
        "HTTP_416_REQUESTED_RANGE_NOT_SATISFIABLE",
    )
    EXPECTATION_FAILED = status.HTTP_417_EXPECTATION_FAILED
    IM_A_TEAPOT = status.HTTP_418_IM_A_TEAPOT
    MISDIRECTED_REQUEST = status.HTTP_421_MISDIRECTED_REQUEST
    UNPROCESSABLE_ENTITY = _http_status(
        "HTTP_422_UNPROCESSABLE_CONTENT",
        "HTTP_422_UNPROCESSABLE_ENTITY",
    )
    LOCKED = status.HTTP_423_LOCKED
    FAILED_DEPENDENCY = status.HTTP_424_FAILED_DEPENDENCY
    TOO_EARLY = status.HTTP_425_TOO_EARLY
    UPGRADE_REQUIRED = status.HTTP_426_UPGRADE_REQUIRED
    PRECONDITION_REQUIRED = status.HTTP_428_PRECONDITION_REQUIRED
    TOO_MANY_REQUESTS = status.HTTP_429_TOO_MANY_REQUESTS
    REQUEST_HEADER_FIELDS_TOO_LARGE = status.HTTP_431_REQUEST_HEADER_FIELDS_TOO_LARGE
    UNAVAILABLE_FOR_LEGAL_REASONS = status.HTTP_451_UNAVAILABLE_FOR_LEGAL_REASONS

    # 5xx Server Errors
    INTERNAL_SERVER_ERROR = status.HTTP_500_INTERNAL_SERVER_ERROR
    NOT_IMPLEMENTED = status.HTTP_501_NOT_IMPLEMENTED
    BAD_GATEWAY = status.HTTP_502_BAD_GATEWAY
    SERVICE_UNAVAILABLE = status.HTTP_503_SERVICE_UNAVAILABLE
    GATEWAY_TIMEOUT = status.HTTP_504_GATEWAY_TIMEOUT
    HTTP_VERSION_NOT_SUPPORTED = status.HTTP_505_HTTP_VERSION_NOT_SUPPORTED
    VARIANT_ALSO_NEGOTIATES = status.HTTP_506_VARIANT_ALSO_NEGOTIATES
    INSUFFICIENT_STORAGE = status.HTTP_507_INSUFFICIENT_STORAGE
    LOOP_DETECTED = status.HTTP_508_LOOP_DETECTED
    NOT_EXTENDED = status.HTTP_510_NOT_EXTENDED
    NETWORK_AUTHENTICATION_REQUIRED = status.HTTP_511_NETWORK_AUTHENTICATION_REQUIRED
