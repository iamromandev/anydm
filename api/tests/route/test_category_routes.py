"""The /category endpoints through the real app, with an in-memory repository behind them."""

import uuid
from collections.abc import AsyncIterator, Iterator
from pathlib import Path

import httpx
import pytest
import pytest_asyncio
from src.config import get_settings
from src.data.type import DOWNLOADS_ID
from src.main import app
from src.service import get_category_service
from src.service.category import CategoryService

from ..service.category.fake_category_repo import FakeCategoryRepo


@pytest.fixture(autouse=True)
def _no_key(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(get_settings(), "api_key", None)


@pytest.fixture
def repo(tmp_path: Path) -> Iterator[FakeCategoryRepo]:
    repo = FakeCategoryRepo()
    app.dependency_overrides[get_category_service] = lambda: CategoryService(repo, tmp_path)
    yield repo
    app.dependency_overrides.clear()


@pytest_asyncio.fixture
async def http() -> AsyncIterator[httpx.AsyncClient]:
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://api.test") as client:
        yield client


@pytest.mark.asyncio
async def test_create_list_patch_order_delete(http: httpx.AsyncClient, repo: FakeCategoryRepo) -> None:
    made = await http.post("/category", json={"name": "Lectures", "folder": "edu/lectures"})
    assert made.status_code == 201
    created = made.json()["data"]
    assert (created["slug"], created["folder"], created["builtin"]) == ("lectures", "edu/lectures", False)

    listed = (await http.get("/category")).json()["data"]["categories"]
    assert listed[0]["id"] == str(DOWNLOADS_ID) and listed[-1]["name"] == "Lectures"

    patched = await http.patch(f"/category/{created['id']}", json={"folder": "edu/talks"})
    assert patched.json()["data"]["folder"] == "edu/talks"

    ids = [row["id"] for row in listed]
    ordered = await http.post("/category/order", json={"ids": list(reversed(ids))})
    assert [row["id"] for row in ordered.json()["data"]["categories"]] == list(reversed(ids))

    assert (await http.delete(f"/category/{created['id']}")).status_code == 204


@pytest.mark.asyncio
async def test_refusals_carry_their_status(http: httpx.AsyncClient, repo: FakeCategoryRepo) -> None:
    assert (await http.post("/category", json={"name": "x", "folder": "../out"})).status_code == 400
    assert (await http.post("/category", json={"name": "Videos"})).status_code == 409
    assert (await http.patch(f"/category/{DOWNLOADS_ID}", json={"folder": "x"})).status_code == 422
    assert (await http.patch(f"/category/{DOWNLOADS_ID}", json={})).status_code == 422
    assert (await http.delete(f"/category/{uuid.uuid4()}")).status_code == 404
