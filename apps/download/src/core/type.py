import json
from enum import Enum, StrEnum
from typing import Any, Self

from fastapi import status


class BaseEnum(Enum):

    @property
    def label(self) -> str:
        if isinstance(self.value, str):
            return self.value.replace("_", " ").title()
        return str(self.value)

    @classmethod
    def value_of(cls: type["BaseEnum"], value: Any) -> "BaseEnum":
        for member in cls:
            if member.value == value:
                return member
        raise ValueError(f"{value} is not a valid value for {cls.__name__}")

    @classmethod
    def list_values(cls) -> list[Any]:
        return [member.value for member in cls]

    @classmethod
    def list_names(cls) -> list[str]:
        return [member.name for member in cls]

    @classmethod
    def to_dict(cls) -> dict[str, Any]:
        return {member.name: member.value for member in cls}

    @classmethod
    def to_json(cls) -> str:
        return json.dumps(cls.to_dict(), ensure_ascii=False)

    @classmethod
    def is_valid_value(cls, value: Any) -> bool:
        return any(member.value == value for member in cls)

    @classmethod
    def is_valid_name(cls, name: str) -> bool:
        return name in cls.__members__

    def __str__(self) -> str:
        return str(self.value)

    def __repr__(self) -> str:
        return f"<{self.__class__.__name__}.{self.name}: {self.value}>"


class Env(BaseEnum):
    LOCAL = "local"
    DEV = "dev"
    PROD = "prod"


class Status(BaseEnum):
    SUCCESS = "success"
    ERROR = "error"


class Code(BaseEnum):
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
    REQUEST_ENTITY_TOO_LARGE = status.HTTP_413_REQUEST_ENTITY_TOO_LARGE
    REQUEST_URI_TOO_LONG = status.HTTP_414_REQUEST_URI_TOO_LONG
    UNSUPPORTED_MEDIA_TYPE = status.HTTP_415_UNSUPPORTED_MEDIA_TYPE
    REQUESTED_RANGE_NOT_SATISFIABLE = status.HTTP_416_REQUESTED_RANGE_NOT_SATISFIABLE
    EXPECTATION_FAILED = status.HTTP_417_EXPECTATION_FAILED
    IM_A_TEAPOT = status.HTTP_418_IM_A_TEAPOT
    MISDIRECTED_REQUEST = status.HTTP_421_MISDIRECTED_REQUEST
    UNPROCESSABLE_ENTITY = status.HTTP_422_UNPROCESSABLE_ENTITY
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


class ErrorType(BaseEnum):
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


class ContentType(str, Enum):
    PDF = "application/pdf"
    TEXT = "text/plain"
    MARKDOWN = "text/markdown"
    HTML = "text/html"
    JSON = "application/json"
    CSV = "text/csv"
    PNG = "image/png"
    JPEG = "image/jpeg"
    JPG = "image/jpg"
    GIF = "image/gif"
    WEBP = "image/webp"
    DOC = "application/msword"
    DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    XLS = "application/vnd.ms-excel"
    XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    PPT = "application/vnd.ms-powerpoint"
    PPTX = "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    ZIP = "application/zip"
    RTF = "application/rtf"


class ModelType(StrEnum):
    URL = "url"
    DATA = "data"

    @classmethod
    def value_of(cls, value: str) -> Self | None:
        try:
            return cls(value)
        except ValueError:
            return None


class Action(StrEnum):
    CREATE = "create"  # CRUD: create
    READ = "read"  # CRUD: read
    UPDATE = "update"  # CRUD: update
    DELETE = "delete"  # CRUD: delete

    FETCH = "fetch"  # Data pipeline: fetch
    CRAWL = "crawl"  # Data pipeline: crawl
    PARSE = "parse"  # Data pipeline: parse
    EXTRACT = "extract"  # Data pipeline: extract
    TRANSFORM = "transform"  # Data pipeline: transform
    LOAD = "load"  # Data pipeline: load
    VALIDATE = "validate"  # Data pipeline: validate
    CLEAN = "clean"  # Data pipeline: clean
    NORMALIZE = "normalize"  # Data pipeline: normalize
    ENRICH = "enrich"  # Data pipeline: enrich
    AGGREGATE = "aggregate"  # Data pipeline: aggregate
    JOIN = "join"  # Data pipeline: join
    FILTER = "filter"  # Data pipeline: filter
    SORT = "sort"  # Data pipeline: sort
    DEDUPLICATE = "deduplicate"  # Data pipeline: deduplicate
    MERGE = "merge"  # Data pipeline: merge
    SPLIT = "split"  # Data pipeline: split

    TRAIN = "train"  # ML/AI: train
    EVALUATE = "evaluate"  # ML/AI: evaluate
    INFER = "infer"  # ML/AI: infer
    PREDICT = "predict"  # ML/AI: predict
    TEST = "test"  # ML/AI: test
    DEPLOY = "deploy"  # ML/AI: deploy
    RETRAIN = "retrain"  # ML/AI: retrain
    TUNE = "tune"  # ML/AI: tune
    SCORE = "score"  # ML/AI: score
    FEATURE_ENGINEER = "feature_engineer"  # ML/AI: feature engineering

    START = "start"  # System/workflow: start
    STOP = "stop"  # System/workflow: stop
    RESTART = "restart"  # System/workflow: restart
    RETRY = "retry"  # System/workflow: retry
    CANCEL = "cancel"  # System/workflow: cancel
    SCHEDULE = "schedule"  # System/workflow: schedule
    PAUSE = "pause"  # System/workflow: pause
    RESUME = "resume"  # System/workflow: resume
    ABORT = "abort"  # System/workflow: abort
    WAIT = "wait"  # System/workflow: wait

    EXPORT = "export"  # Backup/export/import: export
    IMPORT = "import"  # Backup/export/import: import
    BACKUP = "backup"  # Backup/export/import: backup
    RESTORE = "restore"  # Backup/export/import: restore
    SNAPSHOT = "snapshot"  # Backup/export/import: snapshot
    ARCHIVE = "archive"  # Backup/export/import: archive
    CLEANUP = "cleanup"  # Backup/export/import: cleanup

    NOTIFY = "notify"  # Notifications/logging/monitoring: notify
    LOG = "log"  # Notifications/logging/monitoring: log
    MONITOR = "monitor"  # Notifications/logging/monitoring: monitor
    ALERT = "alert"  # Notifications/logging/monitoring: alert
    REPORT = "report"  # Notifications/logging/monitoring: report
    TRACK = "track"  # Notifications/logging/monitoring: track
    AUDIT = "audit"  # Notifications/logging/monitoring: audit
    OBSERVE = "observe"  # Notifications/logging/monitoring: observe

    SEND = "send"  # Communication/messaging: send
    RECEIVE = "receive"  # Communication/messaging: receive
    PUBLISH = "publish"  # Communication/messaging: publish
    SUBSCRIBE = "subscribe"  # Communication/messaging: subscribe
    BROADCAST = "broadcast"  # Communication/messaging: broadcast
    QUEUE = "queue"  # Communication/messaging: queue
    DEQUEUE = "dequeue"  # Communication/messaging: dequeue

    @classmethod
    def value_of(cls, value: str) -> Self | None:
        try:
            return cls(value.lower())
        except ValueError:
            return None


class State(StrEnum):
    NEW = "new"  # Just created, not started yet
    PENDING = "pending"  # Waiting for a resource or dependency
    QUEUED = "queued"  # In queue, scheduled to be processed
    SCHEDULED = "scheduled"  # Assigned for future execution
    INITIALIZING = "initializing"  # Starting up, loading resources
    STARTING = "starting"  # Beginning execution
    RUNNING = "running"  # Actively processing
    PROCESSING = "processing"  # Similar to running, used for data
    VALIDATING = "validating"  # Checking constraints, formats
    CHECKING = "checking"  # Generic verification or check
    TRANSFORMING = "transforming"  # Performing transformation step
    WAITING = "waiting"  # Paused, waiting for event or signal
    BLOCKED = "blocked"  # Cannot proceed due to external issue
    DEFERRED = "deferred"  # Postponed due to unmet conditions
    SKIPPED = "skipped"  # Intentionally not executed
    INTERRUPTED = "interrupted"  # Unexpectedly halted
    RETRY = "retry"  # Scheduled to retry after failure
    TIMEOUT = "timeout"  # Exceeded allowed time limit
    FAILED = "failed"  # Failed during execution
    ERROR = "error"  # Error occurred, similar to failed
    ABORTED = "aborted"  # Manually stopped or aborted
    CANCELED = "canceled"  # Manually or automatically canceled
    COMPLETED = "completed"  # Finished all operations
    SUCCESS = "success"  # Completed successfully
    PARTIAL_SUCCESS = "partial_success"  # Some tasks succeeded
    STALE = "stale"  # Too old or irrelevant to continue
    ARCHIVED = "archived"  # Marked for long-term storage
    EXPIRED = "expired"  # Reached its end of validity
    SUSPENDED = "suspended"  # Temporarily paused
    RESUMED = "resumed"  # Resumed from suspension

    @classmethod
    def value_of(cls, value: str) -> Self | None:
        try:
            return cls(value.lower())
        except ValueError:
            return None


class DataSource(StrEnum):
    API = "api"  # Data from APIs
    USER = "user"  # Direct user input
    CRAWLER = "crawler"  # Web crawling / scraping
    SYSTEM = "system"  # System generated logs/events
    DATABASE = "database"  # Imported from a DB
    FILE_UPLOAD = "file_upload"  # User uploaded files
    SENSOR = "sensor"  # IoT/sensor data
    STREAM = "stream"  # Live streaming sources
    MANUAL = "manual"  # Manual entry
    EXTERNAL_SERVICE = "external_service"  # Third-party service
    MOBILE_APP = "mobile_app"  # Data from mobile applications
    DESKTOP_APP = "desktop_app"  # Data from desktop clients
    EMAIL = "email"  # Extracted from emails
    MESSAGE_QUEUE = "message_queue"  # Kafka/RabbitMQ etc.
    LOG = "log"  # Logs from apps/infra
    BACKUP = "backup"  # Restored from backups
    TEST = "test"  # Synthetic/test data


class DataStatus(StrEnum):
    ACTIVE = "active"  # Currently valid and usable
    INACTIVE = "inactive"  # Not in use but not deleted
    ARCHIVED = "archived"  # Old data kept for record
    DELETED = "deleted"  # Marked for deletion
    PENDING = "pending"  # Awaiting processing or approval
    PROCESSING = "processing"  # In progress of being handled
    FAILED = "failed"  # Data processing failed
    COMPLETED = "completed"  # Fully processed
    DRAFT = "draft"  # Work in progress, not finalized
    REVIEW = "review"  # Under human/auto review
    APPROVED = "approved"  # Reviewed & approved
    REJECTED = "rejected"  # Reviewed & rejected
    EXPIRED = "expired"  # No longer valid due to time
    LOCKED = "locked"  # Frozen, cannot be modified
    SCHEDULED = "scheduled"  # Planned for future activation


class DataVisibility(StrEnum):
    PUBLIC = "public"  # Visible to everyone
    PRIVATE = "private"  # Visible only to owner
    INTERNAL = "internal"  # Restricted to organization/team
    RESTRICTED = "restricted"  # Specific group/role access
    CONFIDENTIAL = "confidential"  # Highly restricted data
    SECRET = "secret"  # Extremely sensitive data
    ANONYMOUS = "anonymous"  # Data visible without attribution
    ENCRYPTED = "encrypted"  # Must be decrypted to be visible
    TOKEN_PROTECTED = "token_protected"  # Requires secure token for access
    PAID = "paid"  # Paywall-protected data
    TEMPORARY = "temporary"  # Visible only for limited time


class DataType(StrEnum):
    DOCUMENT = "document"  # PDFs, Word files, reports
    IMAGE = "image"  # JPG, PNG, GIF
    VIDEO = "video"  # MP4, MOV, AVI
    AUDIO = "audio"  # MP3, WAV, podcasts
    SENSOR = "sensor"  # IoT or sensor readings
    LOG = "log"  # System/app logs
    TRANSACTION = "transaction"  # Financial or business transactions
    WEBPAGE = "webpage"  # HTML pages or web content
    EMAIL = "email"  # Emails, messages
    SCRIPT = "script"  # Code files, automation scripts
    API = "api"  # API responses
    DATABASE = "database"  # DB dumps or rows
    BACKUP = "backup"  # Backup files
    CONFIGURATION = "configuration"  # Config files, YAML/JSON
    ARCHIVE = "archive"  # Compressed files (ZIP, TAR)
    MODEL = "model"  # ML/AI models
    NOTE = "note"  # Notes, markdown, annotations
    STREAM = "stream"  # Streaming data


class DataSubType(StrEnum):
    # Documents
    INVOICE = "invoice"
    REPORT = "report"
    ARTICLE = "article"
    MANUAL = "manual"
    CONTRACT = "contract"
    MEMO = "memo"
    PRESENTATION = "presentation"
    SPREADSHEET = "spreadsheet"

    # Images
    PHOTO = "photo"
    DIAGRAM = "diagram"
    SCREENSHOT = "screenshot"
    ICON = "icon"
    LOGO = "logo"
    MAP = "map"

    # Video
    TUTORIAL = "tutorial"
    ADVERTISEMENT = "advertisement"
    CLIP = "clip"
    MOVIE = "movie"
    ANIMATION = "animation"

    # Audio
    MUSIC = "music"
    PODCAST = "podcast"
    RECORDING = "recording"
    SOUND_EFFECT = "sound_effect"

    # Sensor / IoT
    TEMPERATURE = "temperature"
    HUMIDITY = "humidity"
    GPS = "gps"
    ACCELERATION = "acceleration"
    PRESSURE = "pressure"
    LIGHT = "light"
    PROXIMITY = "proximity"

    # Logs
    ERROR_LOG = "error_log"
    ACCESS_LOG = "access_log"
    EVENT_LOG = "event_log"

    # Transactions
    PAYMENT = "payment"
    ORDER = "order"
    REFUND = "refund"
    INVOICE_ITEM = "invoice_item"

    # Web / API
    HTML_PAGE = "html_page"
    JSON_RESPONSE = "json_response"
    XML_RESPONSE = "xml_response"

    # Code / Scripts
    PYTHON = "python"
    SHELL = "shell"
    JAVASCRIPT = "javascript"
    SQL = "sql"

    # ML / AI
    MODEL_FILE = "model_file"
    TRAINING_DATA = "training_data"

    # Notes / annotations
    MARKDOWN = "markdown"
    NOTE = "note"
