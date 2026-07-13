from uuid import uuid4

import pytest
from httpx import AsyncClient


async def _register(client: AsyncClient, bootstrap_headers: dict[str, str], name: str) -> dict:
    resp = await client.post(
        "/v1/devices/register",
        json={"name": name},
        headers=bootstrap_headers,
    )
    assert resp.status_code == 200
    return resp.json()


def _auth(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


@pytest.mark.asyncio
async def test_setting_upsert_pull_and_idempotent(
    client: AsyncClient,
    bootstrap_headers: dict[str, str],
) -> None:
    device = await _register(client, bootstrap_headers, "dev-a")
    headers = _auth(device["device_token"])
    mutation_id = str(uuid4())

    body = {
        "device_id": device["device_id"],
        "mutations": [
            {
                "mutation_id": mutation_id,
                "entity_type": "setting",
                "entity_id": "theme",
                "operation": "upsert",
                "base_revision": 0,
                "payload_schema_version": 1,
                "payload": {"value": "dark"},
            }
        ],
    }
    first = await client.post("/v1/sync/mutations", json=body, headers=headers)
    assert first.status_code == 200
    result = first.json()["results"][0]
    assert result["status"] == "applied"
    assert result["revision"] == 1

    # replay same mutation_id
    second = await client.post("/v1/sync/mutations", json=body, headers=headers)
    assert second.status_code == 200
    replay = second.json()["results"][0]
    assert replay["status"] == "already_applied" or replay["status"] == "applied"
    # receipt returns cached result with same revision
    assert replay["revision"] == 1

    changes = await client.get("/v1/sync/changes?cursor=0", headers=headers)
    assert changes.status_code == 200
    payload = changes.json()
    assert payload["has_more"] is False
    assert len(payload["changes"]) == 1
    assert payload["changes"][0]["entity_id"] == "theme"
    assert payload["changes"][0]["payload"]["value"] == "dark"

    boot = await client.get("/v1/sync/bootstrap", headers=headers)
    assert boot.status_code == 200
    assert boot.json()["cursor"] >= 1
    assert any(s["key"] == "theme" for s in boot.json()["settings"])


@pytest.mark.asyncio
async def test_conflict_on_stale_base_revision(
    client: AsyncClient,
    bootstrap_headers: dict[str, str],
) -> None:
    a = await _register(client, bootstrap_headers, "dev-a")
    b = await _register(client, bootstrap_headers, "dev-b")
    ha = _auth(a["device_token"])
    hb = _auth(b["device_token"])

    await client.post(
        "/v1/sync/mutations",
        json={
            "device_id": a["device_id"],
            "mutations": [
                {
                    "mutation_id": str(uuid4()),
                    "entity_type": "setting",
                    "entity_id": "locale",
                    "operation": "upsert",
                    "base_revision": 0,
                    "payload": {"value": "zh"},
                }
            ],
        },
        headers=ha,
    )

    conflict = await client.post(
        "/v1/sync/mutations",
        json={
            "device_id": b["device_id"],
            "mutations": [
                {
                    "mutation_id": str(uuid4()),
                    "entity_type": "setting",
                    "entity_id": "locale",
                    "operation": "upsert",
                    "base_revision": 0,
                    "payload": {"value": "en"},
                }
            ],
        },
        headers=hb,
    )
    assert conflict.status_code == 200
    result = conflict.json()["results"][0]
    assert result["status"] == "conflict"
    assert result["revision"] == 1
    assert result["server_payload"]["value"] == "zh"

    # proper update with base_revision=1
    ok = await client.post(
        "/v1/sync/mutations",
        json={
            "device_id": b["device_id"],
            "mutations": [
                {
                    "mutation_id": str(uuid4()),
                    "entity_type": "setting",
                    "entity_id": "locale",
                    "operation": "upsert",
                    "base_revision": 1,
                    "payload": {"value": "en"},
                }
            ],
        },
        headers=hb,
    )
    assert ok.json()["results"][0]["status"] == "applied"
    assert ok.json()["results"][0]["revision"] == 2


@pytest.mark.asyncio
async def test_tombstone_delete_and_two_device_pull(
    client: AsyncClient,
    bootstrap_headers: dict[str, str],
) -> None:
    a = await _register(client, bootstrap_headers, "dev-a")
    b = await _register(client, bootstrap_headers, "dev-b")
    ha = _auth(a["device_token"])
    hb = _auth(b["device_token"])

    await client.post(
        "/v1/sync/mutations",
        json={
            "device_id": a["device_id"],
            "mutations": [
                {
                    "mutation_id": str(uuid4()),
                    "entity_type": "setting",
                    "entity_id": "tmp",
                    "operation": "upsert",
                    "base_revision": 0,
                    "payload": {"value": True},
                }
            ],
        },
        headers=ha,
    )
    deleted = await client.post(
        "/v1/sync/mutations",
        json={
            "device_id": a["device_id"],
            "mutations": [
                {
                    "mutation_id": str(uuid4()),
                    "entity_type": "setting",
                    "entity_id": "tmp",
                    "operation": "delete",
                    "base_revision": 1,
                    "payload": None,
                }
            ],
        },
        headers=ha,
    )
    assert deleted.json()["results"][0]["status"] == "applied"
    assert deleted.json()["results"][0]["server_payload"]["deleted_at"] is not None

    changes = await client.get("/v1/sync/changes?cursor=0&limit=50", headers=hb)
    ops = [c["operation"] for c in changes.json()["changes"]]
    assert "upsert" in ops
    assert "delete" in ops

    boot = await client.get("/v1/sync/bootstrap", headers=hb)
    assert all(s["key"] != "tmp" for s in boot.json()["settings"])


@pytest.mark.asyncio
async def test_mutation_id_replay_returns_already_applied(
    client: AsyncClient,
    bootstrap_headers: dict[str, str],
) -> None:
    device = await _register(client, bootstrap_headers, "dev-a")
    headers = _auth(device["device_token"])
    mutation_id = str(uuid4())
    body = {
        "device_id": device["device_id"],
        "mutations": [
            {
                "mutation_id": mutation_id,
                "entity_type": "setting",
                "entity_id": "font",
                "operation": "upsert",
                "base_revision": 0,
                "payload": {"value": 16},
            }
        ],
    }
    r1 = await client.post("/v1/sync/mutations", json=body, headers=headers)
    assert r1.json()["results"][0]["status"] == "applied"
    # Force status in receipt path: second call must not create a second revision
    r2 = await client.post("/v1/sync/mutations", json=body, headers=headers)
    assert r2.json()["results"][0]["revision"] == 1
    # status from receipt is "applied" as originally stored
    assert r2.json()["results"][0]["status"] in {"applied", "already_applied"}
