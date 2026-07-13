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
async def test_assistant_upsert_delete_and_bootstrap(
    client: AsyncClient,
    bootstrap_headers: dict[str, str],
) -> None:
    device = await _register(client, bootstrap_headers, "dev-a")
    headers = _auth(device["device_token"])
    assistant_id = str(uuid4())

    upsert = await client.post(
        "/v1/sync/mutations",
        json={
            "device_id": device["device_id"],
            "mutations": [
                {
                    "mutation_id": str(uuid4()),
                    "entity_type": "assistant",
                    "entity_id": assistant_id,
                    "operation": "upsert",
                    "base_revision": 0,
                    "payload": {
                        "name": "Helper",
                        "payload": {"id": assistant_id, "name": "Helper", "systemPrompt": "hi"},
                    },
                }
            ],
        },
        headers=headers,
    )
    assert upsert.status_code == 200
    assert upsert.json()["results"][0]["status"] == "applied"
    assert upsert.json()["results"][0]["revision"] == 1

    boot = await client.get("/v1/sync/bootstrap", headers=headers)
    assert boot.status_code == 200
    assistants = boot.json()["assistants"]
    assert any(a["id"] == assistant_id and a["name"] == "Helper" for a in assistants)

    deleted = await client.post(
        "/v1/sync/mutations",
        json={
            "device_id": device["device_id"],
            "mutations": [
                {
                    "mutation_id": str(uuid4()),
                    "entity_type": "assistant",
                    "entity_id": assistant_id,
                    "operation": "delete",
                    "base_revision": 1,
                    "payload": None,
                }
            ],
        },
        headers=headers,
    )
    assert deleted.json()["results"][0]["status"] == "applied"
    assert deleted.json()["results"][0]["server_payload"]["deleted_at"] is not None

    boot2 = await client.get("/v1/sync/bootstrap", headers=headers)
    assert all(a["id"] != assistant_id for a in boot2.json()["assistants"])
