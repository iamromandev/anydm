"""Expand a pattern such as ``img[001-120].png`` into the links it names.

A bracket group expands when it is two numbers (``[1-9]``, ``[01-50]``) or two
letters of one case (``[a-z]``, ``[A-F]``). The left number sets the padding:
``[01-50]`` gives ``01`` .. ``50``, ``[1-9]`` and ``[8-12]`` pad nothing. Any
other bracket stays as written, so a link that really contains ``[`` or ``]``
is not mangled. Several groups multiply, the last one varying fastest.
"""

from __future__ import annotations

import itertools
import re
from math import prod

#: The most links a pattern, or a pasted list, may name.
MAX_ITEMS = 1000

_GROUP = re.compile(r"\[(?:(\d+)-(\d+)|([a-z])-([a-z])|([A-Z])-([A-Z]))\]")


class PatternError(ValueError):
    """The pattern cannot be expanded: a range that runs backwards, or too many links."""


def _digits(left: str, right: str) -> list[str]:
    start, end = int(left), int(right)
    if start > end:
        raise PatternError(f"The range [{left}-{right}] runs backwards")
    # A leading zero on the left number asks for padding to its width.
    width = len(left) if len(left) > 1 and left.startswith("0") else 0
    return [str(number).zfill(width) for number in range(start, end + 1)]


def _letters(left: str, right: str) -> list[str]:
    if left > right:
        raise PatternError(f"The range [{left}-{right}] runs backwards")
    return [chr(code) for code in range(ord(left), ord(right) + 1)]


def _values(match: re.Match[str]) -> list[str]:
    digits_left, digits_right, lower_left, lower_right, upper_left, upper_right = match.groups()
    if digits_left is not None:
        return _digits(digits_left, digits_right)
    if lower_left is not None:
        return _letters(lower_left, lower_right)
    return _letters(upper_left, upper_right)


def _size(match: re.Match[str]) -> int:
    """How many values a group has, without building them: ``[1-99999999999]`` must not allocate."""
    digits_left, digits_right, *_ = match.groups()
    if digits_left is not None:
        return max(int(digits_right) - int(digits_left) + 1, 0)
    return len(_values(match))


def expand(pattern: str, limit: int = MAX_ITEMS) -> list[str]:
    """Every link ``pattern`` names, in order.

    Raises ``PatternError`` for a range that runs backwards, or when the pattern
    names more than ``limit`` links. The size is worked out first, so a huge
    range is refused before anything is built.
    """
    pattern = pattern.strip()
    if not pattern:
        raise PatternError("The pattern is empty")
    groups = list(_GROUP.finditer(pattern))
    # Backwards ranges are reported before the size, so the message names the real fault.
    for group in groups:
        if group.group(1) is not None and int(group.group(1)) > int(group.group(2)):
            raise PatternError(f"The range [{group.group(1)}-{group.group(2)}] runs backwards")
    total = prod(_size(group) for group in groups)
    if total > limit:
        raise PatternError(f"The pattern names {total:,} links; the most is {limit:,}")
    literals: list[str] = []
    cursor = 0
    for group in groups:
        literals.append(pattern[cursor : group.start()])
        cursor = group.end()
    literals.append(pattern[cursor:])
    values = [_values(group) for group in groups]
    links: list[str] = []
    for combo in itertools.product(*values):
        parts = [literals[0]]
        for value, literal in zip(combo, literals[1:], strict=True):
            parts.extend((value, literal))
        links.append("".join(parts))
    return links
