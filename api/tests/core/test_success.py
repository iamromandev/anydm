import json

from src.core.success import Meta, Success
from src.core.type import Code, Status


def test_ok_wraps_data() -> None:
    resp = Success.ok(data={"a": 1}).to_resp()
    body = json.loads(bytes(resp.body))
    assert resp.status_code == 200
    assert body["status"] == Status.SUCCESS
    assert body["code"] == Code.OK
    assert body["data"] == {"a": 1}
    assert body["timestamp"].endswith("Z")


def test_no_content_has_empty_body() -> None:
    resp = Success(code=Code.NO_CONTENT).to_resp()
    assert resp.status_code == 204
    assert resp.body == b""


def test_meta_is_carried() -> None:
    resp = Success.ok(data=[], meta=Meta(page=2, page_size=10, total=25, total_pages=3)).to_resp()
    body = json.loads(bytes(resp.body))
    assert body["meta"]["page"] == 2
    assert body["meta"]["total_pages"] == 3
