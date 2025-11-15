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

META_FIELDS: set[str] = {
    "id",                 # Video unique ID
    "title",              # Human-readable title
    "fulltitle",          # Full title with any extra formatting
    "description",        # Video description
    "thumbnail",          # Thumbnail URL
    "duration",           # Length in seconds
    "view_count",         # Views
    "like_count",         # Likes
    "comment_count",      # Comments
    "age_limit",          # Age restriction
    "upload_date",        # YYYYMMDD string
    "timestamp",          # Unix epoch upload time
    "webpage_url",        # Original video page URL
    "original_url",       # Requested URL
    "channel",            # Channel name
    "channel_id",         # Channel unique ID
    "channel_url",        # Link to channel
    "channel_follower_count", # Subscribers
    "channel_is_verified",    # Boolean
    "uploader",           # Uploader display name
    "uploader_id",        # Uploader ID
    "uploader_url",       # Uploader profile URL
    "categories",         # List of categories
    "tags",               # List of tags
    "media_type",         # "video" or "audio"
    "was_live",           # True if was live
    "is_live",            # True if currently live
    "live_status",        # Live status string
    "language",           # Language code
    "formats",            # List of available formats (filter as needed)
    "availability",       # "public", "private", etc.
}

FORMAT_FIELDS = [
    "format_id",         # internal yt-dlp code
    "format_note",       # e.g., "low", "360p", "storyboard", "low, DRC"
    "ext",               # container extension (mp4, m4a, webm, mhtml)
    "acodec",            # audio codec
    "vcodec",            # video codec
    "url",               # direct media URL
    "filesize",          # exact file size
    "filesize_approx",   # approximate size
    "asr",               # audio sample rate
    "audio_channels",    # number of audio channels
    "tbr",               # total bitrate
    "abr",               # audio bitrate
    "vbr",               # video bitrate (0 for none)
    "width",             # video width or storyboard thumbnail width
    "height",            # video height or storyboard thumbnail height
    "fps",               # frame rate
    "resolution",        # e.g., "360p", "audio only", "48x27"
    "aspect_ratio",      # width / height ratio
    "container",         # e.g., mp4, m4a_dash, webm_dash, mhtml
    "protocol",          # e.g., https, mhtml
    "available_at",      # unix timestamp when format becomes available
    "dynamic_range",     # e.g., SDR
    "language",          # language code if present
    "quality",
    "has_drm",
    "http_headers",      # headers needed for downloading
    "fragments",         # for storyboards / segmented formats
    "rows",              # storyboard rows
    "columns",           # storyboard columns
    "video_ext",         # explicit video ext
    "audio_ext",         # explicit audio ext
    "format",            # human-readable format string
]