import pytest
from httpx import AsyncClient


@pytest.mark.asyncio
async def test_register_requires_bootstrap(
    client: AsyncClient,
) -> None:
    resp = await client.post("/v1/devices/register", json={"name": "phone-a"})
    assert resp.status_code == 401
    assert resp.json()["error"]["code"] == "invalid_bootstrap_token"


@pytest.mark.asyncio
async def test_register_list_revoke_and_reject_revoked(
    client: AsyncClient,
    bootstrap_headers: dict[str, str],
) -> None:
    reg = await client.post(
        "/v1/devices/register",
        json={"name": "phone-a"},
        headers=bootstrap_headers,
    )
    assert reg.status_code == 200
    payload = reg.json()
    token = payload["device_token"]
    device_id = payload["device_id"]
    assert token
    assert payload["name"] == "phone-a"

    auth = {"Authorization": f"Bearer {token}"}

    listed = await client.get("/v1/devices", headers=auth)
    assert listed.status_code == 200
    devices = listed.json()
    assert len(devices) == 1
    assert devices[0]["id"] == device_id
    assert devices[0]["is_current"] is True

    info = await client.get("/v1/server-info", headers=auth)
    assert info.status_code == 200
    body = info.json()
    assert body["api_version"]
    assert body["min_client_version"]
    assert "database" in body["components"]

    revoked = await client.delete(f"/v1/devices/{device_id}", headers=auth)
    assert revoked.status_code == 200
    assert revoked.json()["revoked_at"] is not None

    after = await client.get("/v1/server-info", headers=auth)
    assert after.status_code == 401
    assert after.json()["error"]["code"] == "unauthorized"


@pytest.mark.asyncio
async def test_invalid_bearer_rejected(client: AsyncClient) -> None:
    resp = await client.get(
        "/v1/devices",
        headers={"Authorization": "Bearer totally-wrong"},
    )
    assert resp.status_code == 401


@pytest.mark.asyncio
async def test_second_device_same_user(
    client: AsyncClient,
    bootstrap_headers: dict[str, str],
) -> None:
    a = await client.post(
        "/v1/devices/register",
        json={"name": "device-a"},
        headers=bootstrap_headers,
    )
    b = await client.post(
        "/v1/devices/register",
        json={"name": "device-b"},
        headers=bootstrap_headers,
    )
    assert a.status_code == 200
    assert b.status_code == 200
    assert a.json()["user_id"] == b.json()["user_id"]
    assert a.json()["device_token"] != b.json()["device_token"]

    listed = await client.get(
        "/v1/devices",
        headers={"Authorization": f"Bearer {a.json()['device_token']}"},
    )
    assert listed.status_code == 200
    assert len(listed.json()) == 2
