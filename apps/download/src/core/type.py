import json
from enum import Enum
from typing import Any, Self

from fastapi import status
from fastapi.responses import FileResponse
from tortoise.fields.base import StrEnum


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

class Action(StrEnum):
    # --- CRUD / Resource management ---
    CREATE = "create"
    READ = "read"
    UPDATE = "update"
    DELETE = "delete"
    UPSERT = "upsert"
    PATCH = "patch"

    # --- Data ingestion & processing ---
    FETCH = "fetch"  # Retrieve data
    DOWNLOAD = "download"  # Download files/data
    UPLOAD = "upload"  # Upload files/data
    PARSE = "parse"  # Parse structured or raw data
    EXTRACT = "extract"  # Extract content or features
    TRANSFORM = "transform"  # Transform or clean data
    LOAD = "load"  # Load data into storage or DB
    VALIDATE = "validate"  # Validate schema or correctness
    CLEAN = "clean"  # Data cleaning / preprocessing

    # --- ML / AI operations ---
    TRAIN = "train"  # Train model
    EVALUATE = "evaluate"  # Evaluate model performance
    INFER = "infer"  # Run inference
    PREDICT = "predict"  # Make predictions
    DEPLOY = "deploy"  # Deploy model
    RETRAIN = "retrain"  # Retrain existing model

    # --- Workflow / System control ---
    START = "start"  # Begin execution
    STOP = "stop"  # Stop execution
    RESTART = "restart"  # Restart task or workflow
    RETRY = "retry"  # Retry failed task
    CANCEL = "cancel"  # Cancel pending or running task
    PAUSE = "pause"  # Temporarily pause task
    RESUME = "resume"  # Resume paused task

    # --- Backup / Archival ---
    BACKUP = "backup"  # Main backup operation
    RESTORE = "restore"  # Restore from backup
    ARCHIVE = "archive"  # Move to long-term storage

    # --- Monitoring / Auditing (reduced) ---
    LOG = "log"
    ALERT = "alert"
    MONITOR = "monitor"
    AUDIT = "audit"
    REPORT = "report"

    # --- Communication / Messaging (reduced) ---
    SEND = "send"
    RECEIVE = "receive"
    PUBLISH = "publish"
    SUBSCRIBE = "subscribe"
    ACK = "ack"
    NACK = "nack"

    @classmethod
    def value_of(cls, value: str) -> Self | None:
        try:
            return cls(value.lower())
        except ValueError:
            return None

class State(StrEnum):
    # --- Initialization / Scheduling ---
    NEW = "new"                  # Just created, not started yet
    PENDING = "pending"          # Waiting for dependency or resource
    QUEUED = "queued"            # In queue for execution
    SCHEDULED = "scheduled"      # Planned for future run

    # --- Startup / Preparation ---
    INITIALIZING = "initializing" # Allocating or loading resources
    STARTING = "starting"         # Beginning execution

    # --- Execution / Processing ---
    RUNNING = "running"           # Actively executing
    VALIDATING = "validating"     # Checking input or results
    TRANSFORMING = "transforming" # Performing data transformation

    # --- Control / Flow ---
    WAITING = "waiting"           # Waiting for event, signal, or resource
    BLOCKED = "blocked"           # Cannot proceed due to external issue
    DEFERRED = "deferred"         # Postponed due to unmet conditions
    RETRYING = "retrying"         # Reattempt after failure
    SUSPENDED = "suspended"       # Paused manually or automatically
    RESUMED = "resumed"           # Resumed from suspension
    SKIPPED = "skipped"           # Intentionally not executed

    # --- Failure / Interruption ---
    TIMEOUT = "timeout"           # Exceeded time limit
    FAILED = "failed"             # Execution failed
    ABORTED = "aborted"           # Manually stopped mid-execution
    CANCELED = "canceled"         # Canceled before starting

    # --- Completion / Terminal ---
    SUCCESS = "success"           # Completed successfully
    PARTIAL_SUCCESS = "partial_success" # Some operations succeeded
    COMPLETED = "completed"       # Finished, regardless of outcome

    # --- Lifecycle / Post-execution ---
    STALE = "stale"               # Outdated or no longer relevant
    EXPIRED = "expired"           # Reached end of validity
    ARCHIVED = "archived"         # Moved to long-term storage

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


class UnicodeFileResponse(FileResponse):
    def __init__(
        self,
        path: str,
        media_type: str | None = None,
        filename: str | None = None,
        headers: dict[str, Any] | None = None,
        **kwargs
    ) -> None:
        headers = headers or {}

        if filename:
            # Strip non-ASCII characters
            safe_filename = filename.encode("ascii", "ignore").decode("ascii")
            headers["Content-Disposition"] = f'attachment; filename="{safe_filename}"'

        super().__init__(path=path, media_type=media_type, headers=headers, **kwargs)


class SafeFileResponse(FileResponse):
    def __init__(self, *args, headers=None, **kwargs):
        headers = headers or {}
        safe_headers = {}
        for k, v in headers.items():
            if isinstance(v, str):
                try:
                    v.encode("latin-1")
                    safe_headers[k] = v
                except UnicodeEncodeError:
                    safe_headers[k] = v.encode("utf-8", "ignore").decode("utf-8")
            else:
                safe_headers[k] = v
        super().__init__(*args, headers=safe_headers, **kwargs)
