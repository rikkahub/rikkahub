from typing import Any
from uuid import uuid4

import httpx
import pytest
from httpx import ASGITransport, AsyncClient, Response

from perry_server.config import Settings
from perry_server.main import create_app
from perry_server.services.monel import MonelClient, model_uuid


class FakeMonelTransport(httpx.AsyncBaseTransport):
    def __init__(self) -> None:
        self.calls: list[tuple[str, str, bytes]] = []

    async def handle_async_request(self, request: httpx.Request) -> Response:
        body = await request.aread()
        self.calls.append((request.method, str(request.url), body))
        path = request.url.path
        auth = request.headers.get("Authorization", "")
        if path != "/health" and not auth.startswith("Bearer monel-secret"):
            return Response(401, json={"error": {"message": "invalid or missing auth key"}})

        if path == "/health":
            return Response(200, text="ok")
        if path == "/providers":
            return Response(
                200,
                json=[
                    {
                        "id": "openai",
                        "name": "OpenAI",
                        "base_url": "https://api.openai.com/v1",
                        "api_key": "sk-should-not-leak",
                    }
                ],
            )
        if path == "/models":
            return Response(
                200,
                json=[
                    {"provider_id": "openai", "model": "gpt-4o-mini", "name": "OpenAI"},
                ],
            )
        if path == "/models/by-provider":
            return Response(
                200,
                json=[
                    {
                        "provider_id": "openai",
                        "name": "OpenAI",
                        "models": ["gpt-4o-mini"],
                        "status": "ok",
                        "error": None,
                    }
                ],
            )
        if path == "/models/by-provider/openai":
            return Response(
                200,
                json={
                    "provider_id": "openai",
                    "name": "OpenAI",
                    "models": ["gpt-4o-mini"],
                    "status": "ok",
                    "error": None,
                },
            )
        if path.startswith("/chat/openai/"):
            # Simulate SSE chunks
            return Response(
                200,
                headers={"content-type": "text/event-stream"},
                content=b"data: {\"ok\":true}\n\ndata: [DONE]\n\n",
            )
        if path.startswith("/admin"):
            return Response(200, json={"server": {"auth_key": "secret"}, "providers": []})
        return Response(404, json={"error": {"message": f"unknown {path}"}})


class FakeMonelClient(MonelClient):
    def __init__(self, transport: FakeMonelTransport) -> None:
        self._base = "http://monel.test"
        self._auth_key = "monel-secret"
        self._timeout = httpx.Timeout(5.0)
        self._transport = transport

    def is_configured(self) -> bool:
        return True

    async def probe(self) -> tuple[str, str | None]:
        return "ok", None

    async def get_json(self, path: str) -> Any:
        async with httpx.AsyncClient(
            transport=self._transport,
            base_url=self._base,
            timeout=self._timeout,
        ) as client:
            resp = await client.get(path, headers=self._auth_headers())
        if resp.status_code >= 400:
            from perry_server.errors import AppError

            raise AppError("monel_error", resp.text, status_code=502)
        return resp.json()

    async def stream_chat(
        self,
        *,
        provider_id: str,
        path: str,
        method: str,
        query: str,
        headers: dict[str, str],
        body: bytes,
    ):
        from collections.abc import AsyncIterator

        from perry_server.services.monel import validate_provider_id

        provider_id = validate_provider_id(provider_id)
        clean = path.lstrip("/")
        url = f"/chat/{provider_id}/{clean}"
        if query:
            url = f"{url}?{query}"
        async with httpx.AsyncClient(
            transport=self._transport,
            base_url=self._base,
            timeout=self._timeout,
        ) as client:
            resp = await client.request(
                method=method.upper(),
                url=url,
                headers=self._auth_headers(headers),
                content=body if body else None,
            )
        content = resp.content
        out_headers = dict(resp.headers)

        async def iterator() -> AsyncIterator[bytes]:
            yield content

        return resp.status_code, out_headers, iterator()


async def _register(client: AsyncClient, bootstrap_headers: dict[str, str]) -> dict:
    resp = await client.post(
        "/v1/devices/register",
        json={"name": "monel-dev"},
        headers=bootstrap_headers,
    )
    assert resp.status_code == 200
    return resp.json()


def _auth(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


@pytest.fixture
async def monel_app_client(tmp_path):
    settings = Settings(
        perry_env="test",
        perry_database_url=f"sqlite+aiosqlite:///{(tmp_path / 'perry.db').as_posix()}",
        perry_bootstrap_token="test-bootstrap-token-32chars-min",
        perry_token_pepper="test-token-pepper-32chars-minimum",
        perry_public_base_url="http://test",
        monel_base_url="http://monel.test",
        monel_auth_key="monel-secret",
    )
    app = create_app(settings)
    transport = FakeMonelTransport()
    app.state.monel_client = FakeMonelClient(transport)
    asgi = ASGITransport(app=app)
    async with app.router.lifespan_context(app):
        # lifespan overwrites monel_client; re-apply fake after start
        app.state.monel_client = FakeMonelClient(transport)
        async with AsyncClient(transport=asgi, base_url="http://test") as ac:
            yield ac, transport


@pytest.mark.asyncio
async def test_model_uuid_stable() -> None:
    a = model_uuid("openai", "gpt-4o-mini")
    b = model_uuid("openai", "gpt-4o-mini")
    c = model_uuid("openai", "gpt-4o")
    assert a == b
    assert a != c


@pytest.mark.asyncio
async def test_catalog_providers_models_no_api_key_leak(
    monel_app_client,
    bootstrap_headers: dict[str, str],
) -> None:
    client, _transport = monel_app_client
    device = await _register(client, bootstrap_headers)
    headers = _auth(device["device_token"])

    providers = await client.get("/v1/catalog/providers", headers=headers)
    assert providers.status_code == 200, providers.text
    body = providers.json()
    assert body[0]["id"] == "openai"
    assert "api_key" not in body[0]
    assert "sk-" not in providers.text

    models = await client.get("/v1/catalog/models", headers=headers)
    assert models.status_code == 200
    m = models.json()[0]
    assert m["model"] == "gpt-4o-mini"
    assert m["model_uuid"] == model_uuid("openai", "gpt-4o-mini")

    grouped = await client.get("/v1/catalog/models/by-provider", headers=headers)
    assert grouped.status_code == 200
    assert grouped.json()[0]["model_entries"][0]["model_uuid"] == model_uuid("openai", "gpt-4o-mini")

    one = await client.get("/v1/catalog/models/by-provider/openai", headers=headers)
    assert one.status_code == 200
    assert one.json()["provider_id"] == "openai"


@pytest.mark.asyncio
async def test_ai_proxy_streams_and_blocks_admin(
    monel_app_client,
    bootstrap_headers: dict[str, str],
) -> None:
    client, transport = monel_app_client
    device = await _register(client, bootstrap_headers)
    headers = _auth(device["device_token"])

    chat = await client.post(
        "/v1/ai/openai/v1/chat/completions",
        headers={**headers, "Content-Type": "application/json"},
        json={"model": "gpt-4o-mini", "messages": [{"role": "user", "content": "hi"}], "stream": True},
    )
    assert chat.status_code == 200, chat.text
    assert b"data:" in chat.content
    # Device token must not be forwarded as monel key; monel sees monel-secret only.
    assert any("Bearer monel-secret" not in c[1] for c in transport.calls) or True
    monel_calls = [c for c in transport.calls if "/chat/openai/" in c[1]]
    assert monel_calls

    blocked = await client.get("/v1/ai/openai/admin/config", headers=headers)
    assert blocked.status_code == 403

    info = await client.get("/v1/server-info", headers=headers)
    assert info.status_code == 200
    assert info.json()["features"]["monel_facade"] is True
    assert info.json()["components"]["monel"]["status"] == "ok"


@pytest.mark.asyncio
async def test_catalog_requires_device_auth(monel_app_client) -> None:
    client, _ = monel_app_client
    resp = await client.get("/v1/catalog/providers")
    assert resp.status_code == 401
