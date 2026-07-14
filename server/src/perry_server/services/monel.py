from __future__ import annotations

import re
from collections.abc import AsyncIterator
from typing import Any
from uuid import UUID, uuid5

import httpx

from perry_server.config import Settings
from perry_server.errors import AppError

# Stable namespace for model UUID mapping (Haruhome Phase 8).
HARUHOME_MODEL_NAMESPACE = UUID("6ba7b810-9dad-11d1-80b4-00c04fd430c8")

_HOP_BY_HOP = {
    "connection",
    "keep-alive",
    "proxy-authenticate",
    "proxy-authorization",
    "te",
    "trailers",
    "transfer-encoding",
    "upgrade",
    "host",
    "content-length",
}

_PROVIDER_ID_RE = re.compile(r"^[A-Za-z0-9._-]{1,64}$")


def model_uuid(provider_id: str, upstream_model_id: str) -> str:
    return str(uuid5(HARUHOME_MODEL_NAMESPACE, f"{provider_id}:{upstream_model_id}"))


def validate_provider_id(provider_id: str) -> str:
    if not _PROVIDER_ID_RE.fullmatch(provider_id):
        raise AppError("invalid_provider_id", "invalid provider_id", status_code=400)
    return provider_id


class MonelClient:
    """Server-side Monel gateway client. Android never sees MONEL_AUTH_KEY."""

    def __init__(self, settings: Settings) -> None:
        self._base = (settings.monel_base_url or "").rstrip("/")
        self._auth_key = settings.monel_auth_key or ""
        self._timeout = httpx.Timeout(connect=5.0, read=300.0, write=60.0, pool=5.0)

    def is_configured(self) -> bool:
        return bool(self._base and self._auth_key)

    async def probe(self) -> tuple[str, str | None]:
        if not self.is_configured():
            return "not_configured", "monel not configured"
        try:
            async with httpx.AsyncClient(timeout=httpx.Timeout(3.0)) as client:
                resp = await client.get(f"{self._base}/health")
            if resp.status_code == 200:
                return "ok", None
            return "degraded", f"health status {resp.status_code}"
        except Exception as exc:  # noqa: BLE001
            return "degraded", str(exc)

    def _auth_headers(self, extra: dict[str, str] | None = None) -> dict[str, str]:
        if not self.is_configured():
            raise AppError("monel_not_configured", "Monel is not configured", status_code=503)
        headers = {"Authorization": f"Bearer {self._auth_key}"}
        if extra:
            for k, v in extra.items():
                lk = k.lower()
                if lk in _HOP_BY_HOP or lk == "authorization":
                    continue
                headers[k] = v
        return headers

    async def get_json(self, path: str) -> Any:
        url = f"{self._base}{path}"
        try:
            async with httpx.AsyncClient(timeout=self._timeout) as client:
                resp = await client.get(url, headers=self._auth_headers())
        except httpx.HTTPError as exc:
            raise AppError("monel_unreachable", f"monel request failed: {exc}", status_code=502) from exc
        if resp.status_code == 401:
            raise AppError("monel_unauthorized", "monel auth failed", status_code=502)
        if resp.status_code == 404:
            raise AppError("not_found", resp.text[:500] or "not found", status_code=404)
        if resp.status_code >= 400:
            raise AppError(
                "monel_error",
                resp.text[:500] or f"monel status {resp.status_code}",
                status_code=502,
            )
        if not resp.content:
            return None
        return resp.json()

    async def list_providers(self) -> list[dict[str, Any]]:
        data = await self.get_json("/providers")
        if not isinstance(data, list):
            return []
        out: list[dict[str, Any]] = []
        for item in data:
            if not isinstance(item, dict):
                continue
            pid = str(item.get("id") or "")
            if not pid:
                continue
            # Never forward api_key even if monel misbehaves.
            out.append(
                {
                    "id": pid,
                    "name": str(item.get("name") or pid),
                    "base_url": str(item.get("base_url") or ""),
                }
            )
        return out

    async def list_models(self) -> list[dict[str, Any]]:
        data = await self.get_json("/models")
        if not isinstance(data, list):
            return []
        out: list[dict[str, Any]] = []
        for item in data:
            if not isinstance(item, dict):
                continue
            provider_id = str(item.get("provider_id") or "")
            model = str(item.get("model") or "")
            if not provider_id or not model:
                continue
            out.append(
                {
                    "provider_id": provider_id,
                    "model": model,
                    "name": str(item.get("name") or model),
                    "model_uuid": model_uuid(provider_id, model),
                }
            )
        return out

    async def list_models_by_provider(self) -> list[dict[str, Any]]:
        data = await self.get_json("/models/by-provider")
        if not isinstance(data, list):
            return []
        return [self._enrich_provider_models(item) for item in data if isinstance(item, dict)]

    async def list_models_for_provider(self, provider_id: str) -> dict[str, Any]:
        provider_id = validate_provider_id(provider_id)
        data = await self.get_json(f"/models/by-provider/{provider_id}")
        if not isinstance(data, dict):
            raise AppError("monel_error", "invalid monel response", status_code=502)
        return self._enrich_provider_models(data)

    def _enrich_provider_models(self, item: dict[str, Any]) -> dict[str, Any]:
        provider_id = str(item.get("provider_id") or "")
        models_raw = item.get("models") or []
        models: list[Any] = models_raw if isinstance(models_raw, list) else []
        model_entries: list[dict[str, str]] = []
        for m in models:
            mid = str(m)
            model_entries.append(
                {
                    "id": mid,
                    "model_uuid": model_uuid(provider_id, mid) if provider_id else "",
                }
            )
        return {
            "provider_id": provider_id,
            "name": str(item.get("name") or provider_id),
            "models": [str(m) for m in models],
            "model_entries": model_entries,
            "status": str(item.get("status") or "ok"),
            "error": item.get("error"),
        }

    async def stream_chat(
        self,
        *,
        provider_id: str,
        path: str,
        method: str,
        query: str,
        headers: dict[str, str],
        body: bytes,
    ) -> tuple[int, dict[str, str], AsyncIterator[bytes]]:
        provider_id = validate_provider_id(provider_id)
        clean = path.lstrip("/")
        if ".." in clean.split("/") or clean.startswith("http"):
            raise AppError("invalid_path", "invalid proxy path", status_code=400)
        url = f"{self._base}/chat/{provider_id}/{clean}"
        if query:
            url = f"{url}?{query}"

        client = httpx.AsyncClient(timeout=self._timeout)
        try:
            req = client.build_request(
                method=method.upper(),
                url=url,
                headers=self._auth_headers(headers),
                content=body if body else None,
            )
            resp = await client.send(req, stream=True)
        except httpx.HTTPError as exc:
            await client.aclose()
            raise AppError("monel_unreachable", f"monel proxy failed: {exc}", status_code=502) from exc

        excluded = {
            "content-encoding",
            "content-length",
            "transfer-encoding",
            "connection",
            "keep-alive",
        }
        out_headers = {k: v for k, v in resp.headers.items() if k.lower() not in excluded}

        async def iterator() -> AsyncIterator[bytes]:
            try:
                async for chunk in resp.aiter_raw():
                    yield chunk
            finally:
                await resp.aclose()
                await client.aclose()

        return resp.status_code, out_headers, iterator()


def create_monel_client(settings: Settings) -> MonelClient:
    return MonelClient(settings)
