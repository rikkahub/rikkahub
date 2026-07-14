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
async def test_assistant_memory_upsert_bootstrap_delete(
    client: AsyncClient,
    bootstrap_headers: dict[str, str],
) -> None:
    device = await _register(client, bootstrap_headers, "dev-mem")
    headers = _auth(device["device_token"])
    memory_id = str(uuid4())
    assistant_id = str(uuid4())

    upsert = await client.post(
        "/v1/sync/mutations",
        json={
            "device_id": device["device_id"],
            "mutations": [
                {
                    "mutation_id": str(uuid4()),
                    "entity_type": "assistant_memory",
                    "entity_id": memory_id,
                    "operation": "upsert",
                    "base_revision": 0,
                    "payload": {
                        "payload": {
                            "assistant_id": assistant_id,
                            "content": "User likes concise answers",
                        }
                    },
                }
            ],
        },
        headers=headers,
    )
    assert upsert.status_code == 200, upsert.text
    assert upsert.json()["results"][0]["status"] == "applied"
    assert upsert.json()["results"][0]["revision"] == 1

    # Global memory uses sentinel assistant_id
    global_id = str(uuid4())
    g = await client.post(
        "/v1/sync/mutations",
        json={
            "device_id": device["device_id"],
            "mutations": [
                {
                    "mutation_id": str(uuid4()),
                    "entity_type": "assistant_memory",
                    "entity_id": global_id,
                    "operation": "upsert",
                    "base_revision": 0,
                    "payload": {
                        "payload": {
                            "assistant_id": "__global__",
                            "content": "Global preference",
                        }
                    },
                }
            ],
        },
        headers=headers,
    )
    assert g.json()["results"][0]["status"] == "applied"

    boot = await client.get("/v1/sync/bootstrap", headers=headers)
    assert boot.status_code == 200
    memories = boot.json()["assistant_memories"]
    assert any(m["id"] == memory_id and m["content"].startswith("User likes") for m in memories)
    assert any(m["id"] == global_id and m["assistant_id"] == "__global__" for m in memories)

    deleted = await client.post(
        "/v1/sync/mutations",
        json={
            "device_id": device["device_id"],
            "mutations": [
                {
                    "mutation_id": str(uuid4()),
                    "entity_type": "assistant_memory",
                    "entity_id": memory_id,
                    "operation": "delete",
                    "base_revision": 1,
                    "payload": None,
                }
            ],
        },
        headers=headers,
    )
    assert deleted.json()["results"][0]["status"] == "applied"

    boot2 = await client.get("/v1/sync/bootstrap", headers=headers)
    assert all(m["id"] != memory_id for m in boot2.json()["assistant_memories"])


@pytest.mark.asyncio
async def test_favorite_upsert_bootstrap_delete(
    client: AsyncClient,
    bootstrap_headers: dict[str, str],
) -> None:
    device = await _register(client, bootstrap_headers, "dev-fav")
    headers = _auth(device["device_token"])
    conv_id = str(uuid4())
    node_id = str(uuid4())
    fav_id = f"node:{conv_id}:{node_id}"
    ref_key = fav_id

    upsert = await client.post(
        "/v1/sync/mutations",
        json={
            "device_id": device["device_id"],
            "mutations": [
                {
                    "mutation_id": str(uuid4()),
                    "entity_type": "favorite",
                    "entity_id": fav_id,
                    "operation": "upsert",
                    "base_revision": 0,
                    "payload": {
                        "payload": {
                            "type": "node",
                            "ref_key": ref_key,
                            "ref_json": {
                                "conversationId": conv_id,
                                "nodeId": node_id,
                            },
                            "snapshot_json": "",
                            "meta_json": {"title": "Saved", "previewText": "hi"},
                            "created_at_ms": 1000,
                            "updated_at_ms": 2000,
                        }
                    },
                }
            ],
        },
        headers=headers,
    )
    assert upsert.status_code == 200, upsert.text
    assert upsert.json()["results"][0]["status"] == "applied"

    boot = await client.get("/v1/sync/bootstrap", headers=headers)
    favs = boot.json()["favorites"]
    assert any(f["id"] == fav_id and f["ref_key"] == ref_key for f in favs)

    deleted = await client.post(
        "/v1/sync/mutations",
        json={
            "device_id": device["device_id"],
            "mutations": [
                {
                    "mutation_id": str(uuid4()),
                    "entity_type": "favorite",
                    "entity_id": fav_id,
                    "operation": "delete",
                    "base_revision": 1,
                    "payload": None,
                }
            ],
        },
        headers=headers,
    )
    assert deleted.json()["results"][0]["status"] == "applied"
    boot2 = await client.get("/v1/sync/bootstrap", headers=headers)
    assert all(f["id"] != fav_id for f in boot2.json()["favorites"])
