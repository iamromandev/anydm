"""The /source endpoints through the real app, with an in-memory repository behind them."""

import uuid
from collections.abc import AsyncIterator, Iterator

import httpx
import pytest
import pytest_asyncio
from pydantic import SecretStr
from src.config import get_settings
from src.data.repo.search.interface.source import SearchSourceRow
from src.lib.sources.registry import BUILTINS
from src.main import app
from src.service import get_source_service
from src.service.source.source_service import SourceService

from ..service.search.fake_source_repo import FakeSourceRepo

APIBAY = BUILTINS["apibay"]
APIBAY_ANSWER = (
    b'[{"id":"1","name":"Ubuntu","info_hash":"' + b"a" * 40 + b'","seeders":"5","leechers":"1","size":"10","added":"1790000000","category":"303"}]'
)


@pytest.fixture(autouse=True)
def _no_key(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(get_settings(), "api_key", None)


@pytest.fixture
def repo() -> Iterator[FakeSourceRepo]:
    repo = FakeSourceRepo()
    client = httpx.AsyncClient(transport=httpx.MockTransport(lambda r: httpx.Response(200, content=APIBAY_ANSWER)))
    app.dependency_overrides[get_source_service] = lambda: SourceService(repo, client, timeout_s=1)
    yield repo
    app.dependency_overrides.clear()


@pytest_asyncio.fixture
async def http() -> AsyncIterator[httpx.AsyncClient]:
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://api.test") as client:
        yield client


async def _seed_registry(repo: FakeSourceRepo) -> None:
    await repo.insert_missing(
        [SearchSourceRow(b.name, b.name, b.default_enabled, b.default_url, None) for b in BUILTINS.values()]
    )


@pytest.mark.asyncio
async def test_the_list_shows_registry_first_with_masking_deletable_and_defaults_and_no_label(
    http: httpx.AsyncClient, repo: FakeSourceRepo
) -> None:
    await _seed_registry(repo)
    await http.post("/source", json={"name": "prowlarr", "kind": "torznab", "base_url": "http://p.test/1/api", "api_key": "secret-key-1"})

    body = (await http.get("/source")).json()["data"]

    assert [s["name"] for s in body["sources"]] == ["apibay", "nyaa", "eztv", "prowlarr"]
    assert body["sources"][1]["kind"] == "nyaa"
    assert [s["deletable"] for s in body["sources"]] == [False, False, False, True]
    # The envelope drops nulls, so a missing field is how null arrives.
    assert [s.get("default_url") for s in body["sources"]] == [APIBAY.default_url, "https://nyaa.si", "https://eztvx.to", None]
    assert [s.get("api_key_masked") for s in body["sources"]] == [None, None, None, "secr…ey-1"]
    assert all("label" not in s and "id" in s for s in body["sources"])


@pytest.mark.asyncio
async def test_create_answers_201_and_the_list_remembers(http: httpx.AsyncClient, repo: FakeSourceRepo) -> None:
    created = await http.post("/source", json={"name": "prowlarr", "kind": "torznab", "base_url": "http://p.test/1/api/"})

    assert created.status_code == 201
    assert created.json()["data"]["base_url"] == "http://p.test/1/api"
    assert [s["name"] for s in (await http.get("/source")).json()["data"]["sources"]] == ["prowlarr"]
    assert len(await repo.list_all()) == 1


@pytest.mark.asyncio
async def test_patch_trims_and_the_key_trio_holds(http: httpx.AsyncClient, repo: FakeSourceRepo) -> None:
    await _seed_registry(repo)
    await http.post("/source", json={"name": "prowlarr", "kind": "torznab", "base_url": "http://p.test/1/api", "api_key": "secret-key-1"})
    pid = next(r.id for r in await repo.list_all() if r.name == "prowlarr")

    patched = await http.patch(f"/source/{pid}", json={"enabled": False, "base_url": "https://mirror.test/"})

    assert patched.status_code == 200
    assert (patched.json()["data"]["enabled"], patched.json()["data"]["base_url"]) == (False, "https://mirror.test")
    assert patched.json()["data"]["api_key_masked"] == "secr…ey-1"

    cleared = await http.patch(f"/source/{pid}", json={"api_key": ""})
    assert "api_key_masked" not in cleared.json()["data"]


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "body",
    [
        {"name": "Prowlarr", "kind": "torznab", "base_url": "http://p.test/1/api"},
        {"name": "r", "kind": "rss", "base_url": "http://r.test"},
        {"name": "n", "kind": "nyaa", "base_url": "https://n.test", "api_key": "key-1"},
        {"name": "p", "kind": "torznab", "base_url": "ftp://p.test/1/api"},
        {"name": "p", "kind": "torznab"},
    ],
)
async def test_a_bad_create_body_is_422_and_stores_nothing(http: httpx.AsyncClient, repo: FakeSourceRepo, body: dict[str, object]) -> None:
    assert (await http.post("/source", json=body)).status_code == 422
    assert await repo.list_all() == []


@pytest.mark.asyncio
@pytest.mark.parametrize("body", [{}, {"enabled": None}, {"enabled": "maybe"}])
async def test_a_bad_patch_body_is_422(http: httpx.AsyncClient, repo: FakeSourceRepo, body: dict[str, object]) -> None:
    response = await http.patch(f"/source/{uuid.uuid4()}", json=body)

    assert response.status_code == 422


@pytest.mark.asyncio
async def test_an_unknown_id_is_404_everywhere(http: httpx.AsyncClient, repo: FakeSourceRepo) -> None:
    missing = uuid.uuid4()

    for response in (
        await http.patch(f"/source/{missing}", json={"enabled": True}),
        await http.delete(f"/source/{missing}"),
        await http.post(f"/source/{missing}/reset"),
        await http.post(f"/source/{missing}/test", json={}),
    ):
        assert response.status_code == 404
        assert response.json()["type"] == "source_not_found"


@pytest.mark.asyncio
async def test_delete_is_204_and_the_row_is_gone(http: httpx.AsyncClient, repo: FakeSourceRepo) -> None:
    await _seed_registry(repo)
    await http.post("/source", json={"name": "prowlarr", "kind": "torznab", "base_url": "http://p.test/1/api"})
    pid = next(r.id for r in await repo.list_all() if r.name == "prowlarr")
    nyaa = next(r.id for r in await repo.list_all() if r.name == "nyaa")

    deleted = await http.delete(f"/source/{pid}")

    assert deleted.status_code == 204
    assert [s["name"] for s in (await http.get("/source")).json()["data"]["sources"]] == ["apibay", "nyaa", "eztv"]

    refused = await http.delete(f"/source/{nyaa}")
    assert refused.status_code == 422
    assert refused.json()["type"] == "source_not_deletable"


@pytest.mark.asyncio
async def test_reset_puts_a_source_back(http: httpx.AsyncClient, repo: FakeSourceRepo) -> None:
    await _seed_registry(repo)
    apibay = next(r.id for r in await repo.list_all() if r.name == "apibay")
    await http.patch(f"/source/{apibay}", json={"enabled": False, "base_url": "https://mirror.test"})

    reset = (await http.post(f"/source/{apibay}/reset")).json()["data"]

    assert reset["enabled"] is True and reset["base_url"] == reset["default_url"] == APIBAY.default_url


@pytest.mark.asyncio
async def test_test_and_probe_answer_ok_with_a_count(http: httpx.AsyncClient, repo: FakeSourceRepo) -> None:
    await _seed_registry(repo)
    apibay = next(r.id for r in await repo.list_all() if r.name == "apibay")

    tested = (await http.post(f"/source/{apibay}/test", json={})).json()["data"]
    probed = (await http.post("/source/test", json={"kind": "apibay", "base_url": APIBAY.default_url})).json()["data"]

    assert (tested["ok"], tested["count"], tested["message"]) == (True, 1, "Answered")
    assert (probed["ok"], probed["count"]) == (True, 1)


@pytest.mark.asyncio
async def test_the_api_key_is_still_required(http: httpx.AsyncClient, repo: FakeSourceRepo, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(get_settings(), "api_key", SecretStr("secret"))

    assert (await http.get("/source")).status_code == 401
    assert (await http.get("/source", headers={"X-API-Key": "secret"})).status_code == 200
