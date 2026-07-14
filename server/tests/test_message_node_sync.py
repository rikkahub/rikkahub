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


async def _create_conversation(
    client: AsyncClient,
    headers: dict[str, str],
    device_id: str,
    conv_id: str,
    assistant_id: str,
) -> None:
    resp = await client.post(
        "/v1/sync/mutations",
        json={
            "device_id": device_id,
            "mutations": [
                {
                    "mutation_id": str(uuid4()),
                    "entity_type": "conversation",
                    "entity_id": conv_id,
                    "operation": "upsert",
                    "base_revision": 0,
                    "payload": {
                        "payload": {
                            "assistant_id": assistant_id,
                            "title": "Nodes chat",
                            "create_at_ms": 1000,
                            "update_at_ms": 2000,
                            "is_pinned": False,
                            "folder_id": "",
                            "sync_enabled": True,
                        }
                    },
                }
            ],
        },
        headers=headers,
    )
    assert resp.status_code == 200, resp.text
    assert resp.json()["results"][0]["status"] == "applied"


@pytest.mark.asyncio
async def test_message_node_upsert_list_delta_delete(
    client: AsyncClient,
    bootstrap_headers: dict[str, str],
) -> None:
    device = await _register(client, bootstrap_headers, "dev-nodes")
    headers = _auth(device["device_token"])
    conv_id = str(uuid4())
    assistant_id = str(uuid4())
    node_id = str(uuid4())
    await _create_conversation(client, headers, device["device_id"], conv_id, assistant_id)

    upsert = await client.post(
        "/v1/sync/mutations",
        json={
            "device_id": device["device_id"],
            "mutations": [
                {
                    "mutation_id": str(uuid4()),
                    "entity_type": "message_node",
                    "entity_id": node_id,
                    "operation": "upsert",
                    "base_revision": 0,
                    "payload": {
                        "payload": {
                            "conversation_id": conv_id,
                            "node_index": 0,
                            "select_index": 0,
                            "messages": [
                                {
                                    "id": str(uuid4()),
                                    "role": "user",
                                    "parts": [{"type": "text", "text": "hi"}],
                                }
                            ],
                        }
                    },
                }
            ],
        },
        headers=headers,
    )
    assert upsert.status_code == 200, upsert.text
    body = upsert.json()
    assert body["results"][0]["status"] == "applied"
    assert body["results"][0]["revision"] == 1

    listed = await client.get(f"/v1/conversations/{conv_id}/nodes", headers=headers)
    assert listed.status_code == 200, listed.text
    items = listed.json()["items"]
    assert len(items) == 1
    assert items[0]["id"] == node_id
    assert items[0]["messages"][0]["role"] == "user"

    changes = await client.get(
        f"/v1/conversations/{conv_id}/nodes/changes",
        params={"since_revision": 0},
        headers=headers,
    )
    assert changes.status_code == 200
    assert changes.json()["max_revision"] >= 1
    assert any(i["id"] == node_id for i in changes.json()["items"])

    got = await client.get(f"/v1/conversations/{conv_id}/nodes/{node_id}", headers=headers)
    assert got.status_code == 200
    assert got.json()["node_index"] == 0

    # Update messages / branch select
    update = await client.post(
        "/v1/sync/mutations",
        json={
            "device_id": device["device_id"],
            "mutations": [
                {
                    "mutation_id": str(uuid4()),
                    "entity_type": "message_node",
                    "entity_id": node_id,
                    "operation": "upsert",
                    "base_revision": 1,
                    "payload": {
                        "payload": {
                            "conversation_id": conv_id,
                            "node_index": 0,
                            "select_index": 1,
                            "messages": [
                                {
                                    "id": str(uuid4()),
                                    "role": "user",
                                    "parts": [{"type": "text", "text": "hi"}],
                                },
                                {
                                    "id": str(uuid4()),
                                    "role": "assistant",
                                    "parts": [{"type": "text", "text": "branch-a"}],
                                },
                                {
                                    "id": str(uuid4()),
                                    "role": "assistant",
                                    "parts": [{"type": "text", "text": "branch-b"}],
                                },
                            ],
                        }
                    },
                }
            ],
        },
        headers=headers,
    )
    assert update.json()["results"][0]["status"] == "applied"
    assert update.json()["results"][0]["revision"] == 2

    got2 = await client.get(f"/v1/conversations/{conv_id}/nodes/{node_id}", headers=headers)
    assert got2.json()["select_index"] == 1
    assert len(got2.json()["messages"]) == 3

    deleted = await client.post(
        "/v1/sync/mutations",
        json={
            "device_id": device["device_id"],
            "mutations": [
                {
                    "mutation_id": str(uuid4()),
                    "entity_type": "message_node",
                    "entity_id": node_id,
                    "operation": "delete",
                    "base_revision": 2,
                    "payload": None,
                }
            ],
        },
        headers=headers,
    )
    assert deleted.json()["results"][0]["status"] == "applied"

    listed2 = await client.get(f"/v1/conversations/{conv_id}/nodes", headers=headers)
    assert listed2.json()["items"] == []


@pytest.mark.asyncio
async def test_message_node_index_conflict_tombstones_other(
    client: AsyncClient,
    bootstrap_headers: dict[str, str],
) -> None:
    device = await _register(client, bootstrap_headers, "dev-nodes-idx")
    headers = _auth(device["device_token"])
    conv_id = str(uuid4())
    assistant_id = str(uuid4())
    node_a = str(uuid4())
    node_b = str(uuid4())
    await _create_conversation(client, headers, device["device_id"], conv_id, assistant_id)

    for nid in (node_a,):
        resp = await client.post(
            "/v1/sync/mutations",
            json={
                "device_id": device["device_id"],
                "mutations": [
                    {
                        "mutation_id": str(uuid4()),
                        "entity_type": "message_node",
                        "entity_id": nid,
                        "operation": "upsert",
                        "base_revision": 0,
                        "payload": {
                            "payload": {
                                "conversation_id": conv_id,
                                "node_index": 0,
                                "select_index": 0,
                                "messages": [{"role": "user", "parts": []}],
                            }
                        },
                    }
                ],
            },
            headers=headers,
        )
        assert resp.json()["results"][0]["status"] == "applied"

    # Different node claims same index -> previous live holder is tombstoned
    resp_b = await client.post(
        "/v1/sync/mutations",
        json={
            "device_id": device["device_id"],
            "mutations": [
                {
                    "mutation_id": str(uuid4()),
                    "entity_type": "message_node",
                    "entity_id": node_b,
                    "operation": "upsert",
                    "base_revision": 0,
                    "payload": {
                        "payload": {
                            "conversation_id": conv_id,
                            "node_index": 0,
                            "select_index": 0,
                            "messages": [{"role": "assistant", "parts": []}],
                        }
                    },
                }
            ],
        },
        headers=headers,
    )
    assert resp_b.json()["results"][0]["status"] == "applied"

    listed = await client.get(f"/v1/conversations/{conv_id}/nodes", headers=headers)
    items = listed.json()["items"]
    assert len(items) == 1
    assert items[0]["id"] == node_b
