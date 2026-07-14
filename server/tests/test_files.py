import hashlib
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
async def test_file_init_put_complete_download_and_sync(
    client: AsyncClient,
    bootstrap_headers: dict[str, str],
) -> None:
    device = await _register(client, bootstrap_headers, "dev-files")
    headers = _auth(device["device_token"])
    file_id = str(uuid4())
    payload = b"hello-haruhome-phase7"
    sha = hashlib.sha256(payload).hexdigest()

    init = await client.post(
        "/v1/files/init",
        json={
            "id": file_id,
            "folder": "upload",
            "display_name": "hello.txt",
            "mime_type": "text/plain",
            "size_bytes": len(payload),
            "sha256": sha,
        },
        headers=headers,
    )
    assert init.status_code == 200, init.text
    body = init.json()
    assert body["id"] == file_id
    assert body["upload_status"] == "pending"
    assert body["transfer_mode"] == "proxy"
    assert body["content_path"] == f"/v1/files/{file_id}/content"
    assert body["upload_url"] is None
    assert body["revision"] == 1

    put = await client.put(
        f"/v1/files/{file_id}/content",
        content=payload,
        headers={**headers, "Content-Type": "text/plain"},
    )
    assert put.status_code == 200, put.text

    complete = await client.post(
        f"/v1/files/{file_id}/complete",
        json={"size_bytes": len(payload), "sha256": sha},
        headers=headers,
    )
    assert complete.status_code == 200, complete.text
    assert complete.json()["upload_status"] == "ready"
    assert complete.json()["revision"] >= 2

    meta = await client.get(f"/v1/files/{file_id}", headers=headers)
    assert meta.status_code == 200
    assert meta.json()["sha256"] == sha

    dl = await client.get(f"/v1/files/{file_id}/download-url", headers=headers)
    assert dl.status_code == 200
    assert dl.json()["transfer_mode"] == "proxy"
    assert dl.json()["content_path"] == f"/v1/files/{file_id}/content"
    assert dl.json()["download_url"] == f"/v1/files/{file_id}/content"

    content = await client.get(f"/v1/files/{file_id}/content", headers=headers)
    assert content.status_code == 200
    assert content.content == payload

    boot = await client.get("/v1/sync/bootstrap", headers=headers)
    assert boot.status_code == 200
    files = boot.json()["files"]
    assert any(f["id"] == file_id and f["upload_status"] == "ready" for f in files)

    info = await client.get("/v1/server-info", headers=headers)
    assert info.status_code == 200
    assert info.json()["features"]["files"] is True
    assert info.json()["components"]["minio"]["status"] == "ok"


@pytest.mark.asyncio
async def test_file_mutation_bootstrap_delete(
    client: AsyncClient,
    bootstrap_headers: dict[str, str],
) -> None:
    device = await _register(client, bootstrap_headers, "dev-file-mut")
    headers = _auth(device["device_token"])
    file_id = str(uuid4())

    upsert = await client.post(
        "/v1/sync/mutations",
        json={
            "device_id": device["device_id"],
            "mutations": [
                {
                    "mutation_id": str(uuid4()),
                    "entity_type": "file",
                    "entity_id": file_id,
                    "operation": "upsert",
                    "base_revision": 0,
                    "payload": {
                        "payload": {
                            "folder": "upload",
                            "display_name": "meta-only.png",
                            "mime_type": "image/png",
                            "size_bytes": 12,
                            "sha256": "a" * 64,
                            "object_key": f"users/x/files/{file_id}/meta-only.png",
                            "upload_status": "pending",
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
    assert any(f["id"] == file_id for f in boot.json()["files"])

    deleted = await client.post(
        "/v1/sync/mutations",
        json={
            "device_id": device["device_id"],
            "mutations": [
                {
                    "mutation_id": str(uuid4()),
                    "entity_type": "file",
                    "entity_id": file_id,
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
    assert all(f["id"] != file_id for f in boot2.json()["files"])


@pytest.mark.asyncio
async def test_file_sha_dedup_ready(
    client: AsyncClient,
    bootstrap_headers: dict[str, str],
) -> None:
    device = await _register(client, bootstrap_headers, "dev-dedup")
    headers = _auth(device["device_token"])
    payload = b"same-bytes"
    sha = hashlib.sha256(payload).hexdigest()
    first_id = str(uuid4())
    second_id = str(uuid4())

    init1 = await client.post(
        "/v1/files/init",
        json={
            "id": first_id,
            "display_name": "a.bin",
            "mime_type": "application/octet-stream",
            "size_bytes": len(payload),
            "sha256": sha,
        },
        headers=headers,
    )
    assert init1.status_code == 200
    await client.put(f"/v1/files/{first_id}/content", content=payload, headers=headers)
    done = await client.post(
        f"/v1/files/{first_id}/complete",
        json={"size_bytes": len(payload)},
        headers=headers,
    )
    assert done.status_code == 200

    init2 = await client.post(
        "/v1/files/init",
        json={
            "id": second_id,
            "display_name": "b.bin",
            "mime_type": "application/octet-stream",
            "size_bytes": len(payload),
            "sha256": sha,
        },
        headers=headers,
    )
    assert init2.status_code == 200, init2.text
    body = init2.json()
    assert body["deduplicated"] is True
    assert body["upload_status"] == "ready"
    assert body["transfer_mode"] == "proxy"
    assert body["upload_url"] is None
