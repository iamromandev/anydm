"""What a task row promises the browser about a retry in progress.

A task waiting to be retried is ``pending`` with a future ``next_attempt_at``,
which is indistinguishable from a freshly queued task unless both that deadline
and the attempt budget reach the client.
"""

import uuid
from datetime import UTC, datetime

from src.config import get_settings
from src.data.schema.download import TaskSchema
from src.data.type import Kind, Platform, Preset, TaskStatus


def _row(**overrides: object) -> object:
    fields: dict[str, object] = {
        "id": uuid.uuid4(),
        "source_url": "https://example.com/a.mkv",
        "platform": Platform.DIRECT,
        "preset": Preset.BEST,
        "kind": Kind.FILE,
        "status": TaskStatus.PENDING,
        "attempts": 2,
        "error": "connection reset",
        "error_code": "network",
        "next_attempt_at": datetime(2026, 9, 19, 8, 30, tzinfo=UTC),
    }
    fields.update(overrides)
    return type("Row", (), fields)()


def test_the_retry_deadline_reaches_the_client() -> None:
    schema = TaskSchema.model_validate(_row())

    assert schema.next_attempt_at == datetime(2026, 9, 19, 8, 30, tzinfo=UTC)


def test_the_deadline_serialises_as_an_unambiguous_instant() -> None:
    """Without an offset the browser would read the deadline as local time."""
    encoded = TaskSchema.model_validate(_row()).to_json()

    assert datetime.fromisoformat(encoded["next_attempt_at"]) == datetime(
        2026, 9, 19, 8, 30, tzinfo=UTC
    )


def test_a_task_with_no_retry_pending_omits_the_deadline() -> None:
    encoded = TaskSchema.model_validate(_row(next_attempt_at=None)).to_json()

    assert "next_attempt_at" not in encoded


def test_the_attempt_budget_comes_from_settings() -> None:
    schema = TaskSchema.model_validate(_row())

    assert schema.max_attempts == get_settings().download_max_attempts
    assert schema.max_attempts >= 1
