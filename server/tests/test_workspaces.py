from __future__ import annotations

import asyncio
from collections.abc import AsyncIterator
from uuid import UUID, uuid4

import pytest
import pytest_asyncio
from httpx import ASGITransport, AsyncClient

from perry_server.config import Settings
from perry_server.main import create_app
from perry_server.services.workspace_runtime import (
    PodmanWorkspaceRuntime,
    RuntimeCommandResult,
    RuntimeFileEntry,
    _normalize_cwd,
)


class FakeWorkspaceRuntime(PodmanWorkspaceRuntime):
    def __init__(self) -> None:
        self.files: dict[str, bytes] = {}
        self.ensured: set[UUID] = set()
        self.deleted: set[UUID] = set()

    async def ensure(self, workspace_id: UUID, container_name: str, image: str) -> None:
        self.ensured.add(workspace_id)

    async def delete(self, workspace_id: UUID, container_name: str) -> None:
        self.deleted.add(workspace_id)

    async def execute(
        self,
        workspace_id: UUID,
        container_name: str,
        image: str,
        command: str,
        cwd: str,
        timeout_ms: int,
        stdin: bytes | None,
    ) -> RuntimeCommandResult:
        return RuntimeCommandResult(
            exit_code=7,
            stdout="\x1b[32m完成\x1b[0m\n",
            stderr="warning\n",
            timed_out=False,
            truncated=True,
        )

    async def read_file(
        self, workspace_id: UUID, container_name: str, image: str, path: str
    ) -> bytes:
        return self.files[path]

    async def write_file(
        self,
        workspace_id: UUID,
        container_name: str,
        image: str,
        path: str,
        data: bytes,
        overwrite: bool,
    ) -> RuntimeFileEntry:
        self.files[path] = data
        return self._entry(path)

    async def stat_file(
        self, workspace_id: UUID, container_name: str, image: str, path: str
    ) -> RuntimeFileEntry:
        return self._entry(path)

    async def list_files(
        self, workspace_id: UUID, container_name: str, image: str, path: str
    ) -> list[RuntimeFileEntry]:
        return [self._entry(item) for item in sorted(self.files) if item.startswith(path)]

    async def move_file(
        self,
        workspace_id: UUID,
        container_name: str,
        image: str,
        source: str,
        target: str,
        overwrite: bool,
    ) -> RuntimeFileEntry:
        self.files[target] = self.files.pop(source)
        return self._entry(target)

    async def delete_file(
        self,
        workspace_id: UUID,
        container_name: str,
        image: str,
        path: str,
        recursive: bool,
    ) -> bool:
        return self.files.pop(path, None) is not None

    def _entry(self, path: str) -> RuntimeFileEntry:
        return RuntimeFileEntry(
            path=path,
            name=path.rsplit("/", 1)[-1],
            is_directory=False,
            size_bytes=len(self.files.get(path, b"")),
            updated_at_ms=1_750_000_000_000,
        )


async def _register(
    client: AsyncClient, bootstrap_headers: dict[str, str], name: str
) -> dict[str, str]:
    response = await client.post(
        "/v1/devices/register", json={"name": name}, headers=bootstrap_headers
    )
    assert response.status_code == 200
    return response.json()


def _auth(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


@pytest_asyncio.fixture
async def workspace_client(
    settings: Settings,
) -> AsyncIterator[tuple[AsyncClient, FakeWorkspaceRuntime]]:
    enabled_settings = settings.model_copy(update={"perry_workspace_enabled": True})
    app = create_app(enabled_settings)
    transport = ASGITransport(app=app)
    async with app.router.lifespan_context(app):
        runtime = FakeWorkspaceRuntime()
        app.state.workspace_runtime = runtime
        async with AsyncClient(transport=transport, base_url="http://test") as client:
            yield client, runtime


@pytest.mark.asyncio
async def test_workspace_is_shared_and_preserves_tool_result_fields(
    workspace_client: tuple[AsyncClient, FakeWorkspaceRuntime],
    bootstrap_headers: dict[str, str],
) -> None:
    client, runtime = workspace_client
    device_a = await _register(client, bootstrap_headers, "workspace-a")
    device_b = await _register(client, bootstrap_headers, "workspace-b")
    headers_a = _auth(device_a["device_token"])
    headers_b = _auth(device_b["device_token"])
    workspace_id = str(uuid4())

    created = await client.post(
        "/v1/workspaces",
        json={
            "id": workspace_id,
            "name": "Shared",
            "tool_approvals": {"workspace_shell": True},
        },
        headers=headers_a,
    )
    assert created.status_code == 201
    assert created.json()["shell_status"] == "READY"
    assert UUID(workspace_id) in runtime.ensured

    listed = await client.get("/v1/workspaces", headers=headers_b)
    assert [item["id"] for item in listed.json()["items"]] == [workspace_id]

    executed = await client.post(
        f"/v1/workspaces/{workspace_id}/execute",
        json={"command": "python -V", "cwd": "src", "timeout_ms": 30_000},
        headers=headers_b,
    )
    assert executed.json() == {
        "exit_code": 7,
        "stdout": "\x1b[32m完成\x1b[0m\n",
        "stderr": "warning\n",
        "timed_out": False,
        "truncated": True,
    }

    payload = b"\x89PNG\r\n\x1a\n\x00binary"
    written = await client.put(
        f"/v1/workspaces/{workspace_id}/files/content",
        params={"path": "/workspace/image.png", "overwrite": "true"},
        content=payload,
        headers=headers_a,
    )
    assert written.json()["size_bytes"] == len(payload)
    downloaded = await client.get(
        f"/v1/workspaces/{workspace_id}/files/content",
        params={"path": "/workspace/image.png"},
        headers=headers_b,
    )
    assert downloaded.content == payload

    renamed = await client.patch(
        f"/v1/workspaces/{workspace_id}", json={"name": "Renamed"}, headers=headers_b
    )
    assert renamed.json()["name"] == "Renamed"
    deleted = await client.delete(f"/v1/workspaces/{workspace_id}", headers=headers_a)
    assert deleted.status_code == 204
    assert UUID(workspace_id) in runtime.deleted


@pytest.mark.asyncio
async def test_workspace_creation_does_not_lock_database_during_container_start(
    workspace_client: tuple[AsyncClient, FakeWorkspaceRuntime],
    bootstrap_headers: dict[str, str],
) -> None:
    client, runtime = workspace_client
    device = await _register(client, bootstrap_headers, "slow-workspace")
    headers = _auth(device["device_token"])
    ensure_started = asyncio.Event()
    allow_ensure = asyncio.Event()

    async def slow_ensure(
        workspace_id: UUID,
        container_name: str,
        image: str,
    ) -> None:
        del workspace_id, container_name, image
        ensure_started.set()
        await allow_ensure.wait()

    runtime.ensure = slow_ensure  # type: ignore[method-assign]
    create_task = asyncio.create_task(
        client.post(
            "/v1/workspaces",
            json={"id": str(uuid4()), "name": "Slow"},
            headers=headers,
        )
    )
    await asyncio.wait_for(ensure_started.wait(), timeout=1)
    try:
        second_device = await asyncio.wait_for(
            _register(client, bootstrap_headers, "concurrent-device"),
            timeout=1,
        )
        assert second_device["device_token"]
    finally:
        allow_ensure.set()
    assert (await create_task).status_code == 201


def test_workspace_cwd_cannot_escape_project_volume() -> None:
    assert _normalize_cwd("") == "/workspace"
    assert _normalize_cwd("src") == "/workspace/src"
    with pytest.raises(Exception, match="cwd must stay inside"):
        _normalize_cwd("../etc")
