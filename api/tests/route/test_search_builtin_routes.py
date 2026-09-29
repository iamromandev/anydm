"""The built-in source endpoints through the real app, with an in-memory repository behind them."""

from collections.abc import AsyncIterator, Iterator

import httpx
import pytest
import pytest_asyncio
from pydantic import SecretStr
from src.config import get_settings
from src.main import app
from src.service import get_source_settings_service
from src.service.search.source_settings import SourceSettingsService

from ..service.search.fake_source_repo import FakeSourceRepo

APIBAY = (
    b'[{"id":"1","name":"Ubuntu","info_hash":"' + b"a" * 40 + b'","seeders":"5","leechers":"1","size":"10","added":"1790000000","category":"303"}]'
)


@pytest.fixture(autouse=True)
def _no_key(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(get_settings(), "api_key", None)


@pytest.fixture
def repo() -> Iterator[FakeSourceRepo]:
    repo = FakeSourceRepo()
    client = httpx.AsyncClient(transport=httpx.MockTransport(lambda r: httpx.Response(200, content=APIBAY)))
    app.dependency_overrides[get_source_settings_service] = lambda: SourceSettingsService(repo, client, timeout_s=1)
    yield repo
    app.dependency_overrides.clear()


@pytest_asyncio.fixture
async def http() -> AsyncIterator[httpx.AsyncClient]:
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://api.test") as client:
        yield client


@pytest.mark.asyncio
async def test_the_list_shows_the_three_builtins(http: httpx.AsyncClient, repo: FakeSourceRepo) -> None:
    body = (await http.get("/search/builtin")).json()["data"]

    assert [s["name"] for s in body["sources"]] == ["apibay", "nyaa", "eztv"]
    assert body["sources"][1]["label"] == "Nyaa"
    assert body["sources"][0]["enabled"] is True


@pytest.mark.asyncio
async def test_patch_changes_and_the_list_remembers(http: httpx.AsyncClient, repo: FakeSourceRepo) -> None:
    patched = await http.patch("/search/builtin/nyaa", json={"enabled": False, "base_url": "https://mirror.test/"})

    assert patched.status_code == 200
    assert patched.json()["data"]["enabled"] is False
    assert patched.json()["data"]["base_url"] == "https://mirror.test"
    listed = (await http.get("/search/builtin")).json()["data"]["sources"]
    assert (listed[1]["enabled"], listed[1]["base_url"]) == (False, "https://mirror.test")


@pytest.mark.asyncio
@pytest.mark.parametrize("body", [{}, {"enabled": None}, {"base_url": "ftp://x.test"}, {"base_url": ""}, {"enabled": "maybe"}])
async def test_a_bad_patch_is_422(http: httpx.AsyncClient, repo: FakeSourceRepo, body: dict[str, object]) -> None:
    response = await http.patch("/search/builtin/apibay", json=body)

    assert response.status_code == 422
    assert repo.rows == {}


@pytest.mark.asyncio
async def test_an_unknown_source_is_404_everywhere(http: httpx.AsyncClient, repo: FakeSourceRepo) -> None:
    for response in (
        await http.patch("/search/builtin/nope", json={"enabled": True}),
        await http.post("/search/builtin/nope/reset"),
        await http.post("/search/builtin/nope/test", json={}),
    ):
        assert response.status_code == 404
        assert response.json()["type"] == "source_not_found"


@pytest.mark.asyncio
async def test_reset_puts_a_source_back(http: httpx.AsyncClient, repo: FakeSourceRepo) -> None:
    await http.patch("/search/builtin/apibay", json={"enabled": False, "base_url": "https://mirror.test"})

    reset = (await http.post("/search/builtin/apibay/reset")).json()["data"]

    assert reset["enabled"] is True and reset["base_url"] == reset["default_url"]


@pytest.mark.asyncio
async def test_test_answers_counts_and_never_a_body(http: httpx.AsyncClient, repo: FakeSourceRepo) -> None:
    response = await http.post("/search/builtin/apibay/test", json={"base_url": "https://mirror.test"})
    data = response.json()["data"]

    assert response.status_code == 200
    assert (data["ok"], data["count"], data["message"]) == (True, 1, "Answered")
    assert isinstance(data["took_ms"], int)
    assert "Ubuntu" not in response.text


@pytest.mark.asyncio
async def test_test_without_a_body_asks_the_saved_address(http: httpx.AsyncClient, repo: FakeSourceRepo) -> None:
    response = await http.post("/search/builtin/apibay/test")

    assert response.status_code == 200
    assert response.json()["data"]["ok"] is True


@pytest.mark.asyncio
async def test_the_routes_need_the_api_key_when_one_is_set(http: httpx.AsyncClient, repo: FakeSourceRepo, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(get_settings(), "api_key", SecretStr("secret"))

    assert (await http.get("/search/builtin")).status_code == 401
    assert (await http.get("/search/builtin", headers={"X-API-Key": "secret"})).status_code == 200
