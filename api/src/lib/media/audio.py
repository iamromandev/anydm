"""A source's audio tracks, and which one a player opens with (#99).

A file names a track's language in ISO 639-2 (``eng``, and ``fre`` or ``fra``),
a site in BCP 47 (``en``, ``en-US``). They're compared by ``language_key``,
which folds the common ones onto ISO 639-1. Anything it doesn't know is
compared as it is, which still matches a code against itself.
"""

from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass


@dataclass(frozen=True, slots=True)
class AudioTrack:
    #: Counted among the source's audio tracks only, as ``-map 0:a:N`` counts.
    #: For a site, its place in the page's list of audio formats.
    index: int
    language: str | None = None
    title: str | None = None
    channels: int | None = None
    codec: str | None = None
    #: The track the source marks as the one to open with.
    default: bool = False


#: ISO 639-2 codes, bibliographic and terminological, by the ISO 639-1 code
#: they mean. The languages the Settings menu offers, and a few more.
_TWO_LETTER = {
    "ara": "ar", "ben": "bn", "chi": "zh", "zho": "zh", "cze": "cs", "ces": "cs",
    "dan": "da", "dut": "nl", "nld": "nl", "eng": "en", "fin": "fi", "fre": "fr",
    "fra": "fr", "ger": "de", "deu": "de", "gre": "el", "ell": "el", "heb": "he",
    "hin": "hi", "hun": "hu", "ind": "id", "ita": "it", "jpn": "ja", "kor": "ko",
    "may": "ms", "msa": "ms", "nor": "no", "nob": "no", "nno": "no", "per": "fa",
    "fas": "fa", "pol": "pl", "por": "pt", "rum": "ro", "ron": "ro", "rus": "ru",
    "spa": "es", "swe": "sv", "tam": "ta", "tel": "te", "tha": "th", "tur": "tr",
    "ukr": "uk", "urd": "ur", "vie": "vi", "fil": "tl", "tgl": "tl",
}

#: What a file writes when it doesn't know.
_UNKNOWN = {"und", "unk", "mis", "mul", "zxx", ""}

#: The languages above by their English names, as subtitle files are often
#: named (``2_English.srt``), and a few native ones releases use.
_NAMES = {
    "arabic": "ar", "bengali": "bn", "chinese": "zh", "czech": "cs", "danish": "da",
    "dutch": "nl", "english": "en", "finnish": "fi", "french": "fr", "francais": "fr",
    "german": "de", "deutsch": "de", "greek": "el", "hebrew": "he", "hindi": "hi",
    "hungarian": "hu", "indonesian": "id", "italian": "it", "italiano": "it",
    "japanese": "ja", "korean": "ko", "malay": "ms", "norwegian": "no", "persian": "fa",
    "farsi": "fa", "polish": "pl", "portuguese": "pt", "brazilian": "pt", "romanian": "ro",
    "russian": "ru", "spanish": "es", "espanol": "es", "latino": "es", "swedish": "sv",
    "tamil": "ta", "telugu": "te", "thai": "th", "turkish": "tr", "ukrainian": "uk",
    "urdu": "ur", "vietnamese": "vi", "filipino": "tl", "tagalog": "tl",
}

#: Two-letter codes a file name can carry. Not "hi": beside a language,
#: ``Movie.en.hi.srt`` means hearing impaired far more often than Hindi.
_FILE_NAME_CODES = (set(_TWO_LETTER.values()) | {"en", "es", "pt", "it"}) - {"hi"}


def language_in_name(token: str) -> str | None:
    """The ISO 639-1 code one word of a file name stands for (``en``, ``eng``, ``English``), if any."""
    word = token.strip().lower()
    if word in _NAMES:
        return _NAMES[word]
    if word in _TWO_LETTER:
        return _TWO_LETTER[word]
    return word if word in _FILE_NAME_CODES else None


def language_key(code: str | None) -> str | None:
    """``code``'s language alone, comparable across ISO 639-1, 639-2 and BCP 47; ``None`` when unknown."""
    if not code:
        return None
    primary = code.strip().replace("_", "-").split("-", 1)[0].lower()
    if primary in _UNKNOWN:
        return None
    return _TWO_LETTER.get(primary, primary)


def pick_audio_track(
    tracks: Sequence[AudioTrack], language: str | None = None, wanted: int | None = None
) -> int | None:
    """The track to open with: ``wanted`` if there is one, else the first match below.

    1. the preferred ``language``
    2. the track the source marks as default
    3. the first track

    ``None`` when there are no tracks at all.
    """
    if not tracks:
        return None
    indexes = [track.index for track in tracks]
    if wanted is not None and wanted in indexes:
        return wanted
    key = language_key(language)
    if key is not None:
        match = next((track for track in tracks if language_key(track.language) == key), None)
        if match is not None:
            return match.index
    marked = next((track for track in tracks if track.default), None)
    return (marked or tracks[0]).index
