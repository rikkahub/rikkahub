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
async def test_conversation_upsert_list_bootstrap_delete(
    client: AsyncClient,
    bootstrap_headers: dict[str, str],
) -> None:
    device = await _register(client, bootstrap_headers, "dev-conv")
    headers = _auth(device["device_token"])
    conv_id = str(uuid4())
    assistant_id = str(uuid4())

    upsert = await client.post(
        "/v1/sync/mutations",
        json={
            "device_id": device["device_id"],
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
                            "title": "Hello",
                            "create_at_ms": 1000,
                            "update_at_ms": 2000,
                            "is_pinned": True,
                            "folder_id": "",
                            "sync_enabled": True,
                            "chat_suggestions": ["a"],
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

    listed = await client.get("/v1/conversations", headers=headers)
    assert listed.status_code == 200
    items = listed.json()["items"]
    assert any(i["id"] == conv_id and i["title"] == "Hello" and i["is_pinned"] for i in items)

    boot = await client.get("/v1/sync/bootstrap", headers=headers)
    assert boot.status_code == 200
    assert any(c["id"] == conv_id for c in boot.json()["conversations"])

    got = await client.get(f"/v1/conversations/{conv_id}", headers=headers)
    assert got.status_code == 200
    assert got.json()["title"] == "Hello"

    deleted = await client.post(
        "/v1/sync/mutations",
        json={
            "device_id": device["device_id"],
            "mutations": [
                {
                    "mutation_id": str(uuid4()),
                    "entity_type": "conversation",
                    "entity_id": conv_id,
                    "operation": "delete",
                    "base_revision": 1,
                    "payload": None,
                }
            ],
        },
        headers=headers,
    )
    assert deleted.json()["results"][0]["status"] == "applied"

    listed2 = await client.get("/v1/conversations", headers=headers)
    assert all(i["id"] != conv_id for i in listed2.json()["items"])


@pytest.mark.asyncio
async def test_conversation_list_cursor_pagination(
    client: AsyncClient,
    bootstrap_headers: dict[str, str],
) -> None:
    device = await _register(client, bootstrap_headers, "dev-page")
    headers = _auth(device["device_token"])
    assistant_id = str(uuid4())
    ids: list[str] = []
    for i in range(5):
        cid = str(uuid4())
        ids.append(cid)
        resp = await client.post(
            "/v1/sync/mutations",
            json={
                "device_id": device["device_id"],
                "mutations": [
                    {
                        "mutation_id": str(uuid4()),
                        "entity_type": "conversation",
                        "entity_id": cid,
                        "operation": "upsert",
                        "base_revision": 0,
                        "payload": {
                            "payload": {
                                "assistant_id": assistant_id,
                                "title": f"t{i}",
                                "create_at_ms": 1000 + i,
                                "update_at_ms": 1000 + i,
                                "is_pinned": False,
                            }
                        },
                    }
                ],
            },
            headers=headers,
        )
        assert resp.status_code == 200
        assert resp.json()["results"][0]["status"] == "applied"

    page1 = await client.get("/v1/conversations?limit=2", headers=headers)
    assert page1.status_code == 200
    data1 = page1.json()
    assert len(data1["items"]) == 2
    assert data1["has_more"] is True
    assert data1["next_cursor"]

    page2 = await client.get(
        f"/v1/conversations?limit=2&cursor={data1['next_cursor']}",
        headers=headers,
    )
    assert page2.status_code == 200
    data2 = page2.json()
    ids1 = {i["id"] for i in data1["items"]}
    ids2 = {i["id"] for i in data2["items"]}
    assert ids1.isdisjoint(ids2)
