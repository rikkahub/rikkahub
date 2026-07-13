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
async def test_folder_upsert_bootstrap_delete(
    client: AsyncClient,
    bootstrap_headers: dict[str, str],
) -> None:
    device = await _register(client, bootstrap_headers, "dev-folder")
    headers = _auth(device["device_token"])
    folder_id = str(uuid4())
    assistant_id = str(uuid4())

    upsert = await client.post(
        "/v1/sync/mutations",
        json={
            "device_id": device["device_id"],
            "mutations": [
                {
                    "mutation_id": str(uuid4()),
                    "entity_type": "conversation_folder",
                    "entity_id": folder_id,
                    "operation": "upsert",
                    "base_revision": 0,
                    "payload": {
                        "payload": {
                            "assistant_id": assistant_id,
                            "name": "Work",
                            "sort_index": 1,
                            "create_at_ms": 123,
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
    assert boot.status_code == 200
    folders = boot.json()["conversation_folders"]
    assert any(f["id"] == folder_id and f["name"] == "Work" for f in folders)

    deleted = await client.post(
        "/v1/sync/mutations",
        json={
            "device_id": device["device_id"],
            "mutations": [
                {
                    "mutation_id": str(uuid4()),
                    "entity_type": "conversation_folder",
                    "entity_id": folder_id,
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
    assert all(f["id"] != folder_id for f in boot2.json()["conversation_folders"])
