from __future__ import annotations

import asyncio
import os
import posixpath
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from uuid import UUID

from perry_server.config import Settings
from perry_server.errors import AppError


@dataclass(slots=True)
class RuntimeCommandResult:
    exit_code: int
    stdout: str
    stderr: str
    timed_out: bool = False
    truncated: bool = False


@dataclass(slots=True)
class RuntimeFileEntry:
    path: str
    name: str
    is_directory: bool
    size_bytes: int
    updated_at_ms: int


class PodmanWorkspaceRuntime:
    """Persistent rootless Podman containers, one per cloud workspace."""

    def __init__(self, settings: Settings) -> None:
        self.settings = settings
        self.binary = settings.perry_workspace_podman_binary
        self.data_root = settings.workspace_data_root

    async def ensure(self, workspace_id: UUID, container_name: str, image: str) -> None:
        self._require_enabled()
        label = await self._container_label(container_name)
        if label is None:
            workspace_dir = self._workspace_dir(workspace_id)
            workspace_dir.mkdir(parents=True, exist_ok=True)
            args = [
                "create",
                "--name",
                container_name,
                "--label",
                f"perry.workspace_id={workspace_id}",
                "--user",
                "0",
                "--security-opt",
                "no-new-privileges",
                "--network",
                "slirp4netns:allow_host_loopback=false",
                "--pids-limit",
                str(self.settings.perry_workspace_pids_limit),
                "--memory",
                self.settings.perry_workspace_memory,
                "--cpus",
                str(self.settings.perry_workspace_cpus),
                "--volume",
                f"{workspace_dir}:/workspace:rw,Z",
                "--entrypoint",
                "/bin/sh",
                image,
                "-c",
                "while :; do sleep 3600; done",
            ]
            await self._podman_checked(*args, timeout=300)
        elif label != str(workspace_id):
            raise AppError(
                "workspace_container_conflict",
                "container name is not owned by this workspace",
                status_code=409,
            )

        running = await self._podman_checked(
            "inspect", "--format", "{{.State.Running}}", container_name
        )
        if running.stdout.strip() != "true":
            await self._podman_checked("start", container_name, timeout=60)

    async def delete(self, workspace_id: UUID, container_name: str) -> None:
        label = await self._container_label(container_name)
        if label is not None and label != str(workspace_id):
            raise AppError(
                "workspace_container_conflict",
                "refusing to delete a container owned by another workspace",
                status_code=409,
            )
        if label is not None:
            await self._podman_checked("rm", "--force", container_name, timeout=60)
        workspace_dir = self._workspace_dir(workspace_id)
        if workspace_dir.exists():
            for root, dirs, files in os.walk(workspace_dir, topdown=False):
                for name in files:
                    Path(root, name).unlink(missing_ok=True)
                for name in dirs:
                    Path(root, name).rmdir()
            workspace_dir.rmdir()
        workspace_parent = workspace_dir.parent
        try:
            workspace_parent.rmdir()
        except OSError:
            pass

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
        await self.ensure(workspace_id, container_name, image)
        workdir = _normalize_cwd(cwd)
        timeout_seconds = max(1, (timeout_ms + 999) // 1000)
        return await self._podman_limited(
            "exec",
            "--interactive",
            "--workdir",
            workdir,
            container_name,
            "/usr/bin/timeout",
            "--signal=TERM",
            "--kill-after=2s",
            f"{timeout_seconds}s",
            "/bin/sh",
            "-lc",
            command,
            stdin=stdin,
            timeout=(timeout_ms / 1000) + 5,
        )

    async def read_file(
        self,
        workspace_id: UUID,
        container_name: str,
        image: str,
        path: str,
    ) -> bytes:
        await self.ensure(workspace_id, container_name, image)
        normalized = _normalize_absolute_path(path)
        entry = await self.stat_file(workspace_id, container_name, image, normalized)
        if entry.is_directory:
            raise AppError("not_a_file", "path is a directory", status_code=400)
        if entry.size_bytes > self.settings.perry_workspace_max_file_bytes:
            raise AppError("file_too_large", "workspace file exceeds read limit", status_code=413)
        code, stdout, stderr, truncated = await self._podman_bytes(
            "exec",
            container_name,
            "/bin/sh",
            "-c",
            'cat -- "$1"',
            "sh",
            normalized,
            limit=self.settings.perry_workspace_max_file_bytes,
        )
        if truncated:
            raise AppError("file_too_large", "workspace file exceeds read limit", status_code=413)
        if code != 0:
            raise AppError(
                "workspace_io_error",
                stderr.decode(errors="replace"),
                status_code=400,
            )
        return stdout

    async def write_file(
        self,
        workspace_id: UUID,
        container_name: str,
        image: str,
        path: str,
        data: bytes,
        overwrite: bool,
    ) -> RuntimeFileEntry:
        await self.ensure(workspace_id, container_name, image)
        if len(data) > self.settings.perry_workspace_max_file_bytes:
            raise AppError("file_too_large", "workspace file exceeds write limit", status_code=413)
        normalized = _normalize_absolute_path(path)
        script = """
set -eu
target=$1
overwrite=$2
if [ -e "$target" ] && [ "$overwrite" != 1 ]; then
  echo "file already exists" >&2
  exit 17
fi
if [ -e "$target" ] && [ ! -f "$target" ]; then
  echo "path is not a file" >&2
  exit 21
fi
mkdir -p -- "$(dirname -- "$target")"
cat > "$target"
""".strip()
        result = await self._podman_limited(
            "exec",
            "--interactive",
            container_name,
            "/bin/sh",
            "-c",
            script,
            "sh",
            normalized,
            "1" if overwrite else "0",
            stdin=data,
            timeout=35,
        )
        if result.exit_code != 0:
            raise AppError("workspace_io_error", result.stderr or "write failed", status_code=409)
        return await self.stat_file(workspace_id, container_name, image, normalized)

    async def stat_file(
        self,
        workspace_id: UUID,
        container_name: str,
        image: str,
        path: str,
    ) -> RuntimeFileEntry:
        await self.ensure(workspace_id, container_name, image)
        normalized = _normalize_absolute_path(path)
        result = await self._podman(
            "exec",
            container_name,
            "/bin/sh",
            "-c",
            'stat -c "%F\037%s\037%Y" -- "$1"',
            "sh",
            normalized,
        )
        if result.exit_code != 0:
            if "No such file" in result.stderr or "cannot stat" in result.stderr:
                raise AppError("file_not_found", "workspace path does not exist", status_code=404)
            raise AppError("workspace_io_error", result.stderr or "stat failed", status_code=400)
        fields = result.stdout.rstrip("\n").split("\x1f")
        if len(fields) != 3:
            raise AppError("workspace_io_error", "invalid stat response", status_code=500)
        return RuntimeFileEntry(
            path=normalized,
            name=PurePosixPath(normalized).name or "/",
            is_directory=fields[0] == "directory",
            size_bytes=int(fields[1]),
            updated_at_ms=int(float(fields[2]) * 1000),
        )

    async def list_files(
        self,
        workspace_id: UUID,
        container_name: str,
        image: str,
        path: str,
    ) -> list[RuntimeFileEntry]:
        await self.ensure(workspace_id, container_name, image)
        normalized = _normalize_absolute_path(path)
        script = 'find "$1" -mindepth 1 -maxdepth 1 -printf "%f\\0%y\\0%s\\0%T@\\0"'
        code, stdout, stderr, truncated = await self._podman_bytes(
            "exec",
            container_name,
            "/bin/sh",
            "-c",
            script,
            "sh",
            normalized,
            limit=self.settings.perry_workspace_max_output_bytes,
        )
        if truncated:
            raise AppError(
                "directory_too_large",
                "workspace directory listing exceeds output limit",
                status_code=413,
            )
        if code != 0:
            message = stderr.decode(errors="replace")
            status_code = 404 if "No such file" in message else 400
            raise AppError("workspace_io_error", message, status_code=status_code)
        fields = stdout.split(b"\0")
        if fields and fields[-1] == b"":
            fields.pop()
        if len(fields) % 4 != 0:
            raise AppError("workspace_io_error", "invalid directory listing", status_code=500)
        entries: list[RuntimeFileEntry] = []
        for index in range(0, len(fields), 4):
            name = fields[index].decode(errors="replace")
            entries.append(
                RuntimeFileEntry(
                    path=posixpath.join(normalized.rstrip("/"), name),
                    name=name,
                    is_directory=fields[index + 1] == b"d",
                    size_bytes=int(fields[index + 2]),
                    updated_at_ms=int(float(fields[index + 3]) * 1000),
                )
            )
        return sorted(entries, key=lambda item: (not item.is_directory, item.name.lower()))

    async def move_file(
        self,
        workspace_id: UUID,
        container_name: str,
        image: str,
        source: str,
        target: str,
        overwrite: bool,
    ) -> RuntimeFileEntry:
        await self.ensure(workspace_id, container_name, image)
        source_path = _normalize_absolute_path(source)
        target_path = _normalize_absolute_path(target)
        script = """
set -eu
source=$1
target=$2
overwrite=$3
if [ -e "$target" ] && [ "$overwrite" != 1 ]; then exit 17; fi
mkdir -p -- "$(dirname -- "$target")"
if [ "$overwrite" = 1 ]; then rm -rf -- "$target"; fi
mv -- "$source" "$target"
""".strip()
        result = await self._podman_checked(
            "exec",
            container_name,
            "/bin/sh",
            "-c",
            script,
            "sh",
            source_path,
            target_path,
            "1" if overwrite else "0",
        )
        if result.exit_code != 0:
            raise AppError("workspace_io_error", result.stderr or "move failed", status_code=409)
        return await self.stat_file(workspace_id, container_name, image, target_path)

    async def delete_file(
        self,
        workspace_id: UUID,
        container_name: str,
        image: str,
        path: str,
        recursive: bool,
    ) -> bool:
        await self.ensure(workspace_id, container_name, image)
        normalized = _normalize_absolute_path(path)
        if normalized in {"/", "/workspace"}:
            raise AppError("unsafe_path", "refusing to delete workspace root", status_code=400)
        script = 'if [ ! -e "$1" ] && [ ! -L "$1" ]; then exit 44; fi; rm "$2" -- "$1"'
        result = await self._podman(
            "exec",
            container_name,
            "/bin/sh",
            "-c",
            script,
            "sh",
            normalized,
            "-rf" if recursive else "-f",
        )
        if result.exit_code == 44:
            return False
        if result.exit_code != 0:
            raise AppError("workspace_io_error", result.stderr or "delete failed", status_code=409)
        return True

    async def _container_label(self, container_name: str) -> str | None:
        self._require_enabled()
        result = await self._podman(
            "inspect",
            "--format",
            '{{index .Config.Labels "perry.workspace_id"}}',
            container_name,
        )
        if result.exit_code != 0:
            return None
        return result.stdout.strip()

    def _workspace_dir(self, workspace_id: UUID) -> Path:
        path = (self.data_root / str(workspace_id) / "workspace").resolve()
        if self.data_root not in path.parents:
            raise AppError("unsafe_path", "workspace path escaped data root", status_code=500)
        return path

    def _require_enabled(self) -> None:
        if not self.settings.perry_workspace_enabled:
            raise AppError("workspace_disabled", "cloud workspaces are disabled", status_code=503)

    async def _podman_checked(self, *args: str, timeout: float = 30) -> RuntimeCommandResult:
        result = await self._podman(*args, timeout=timeout)
        if result.exit_code != 0:
            raise AppError(
                "workspace_runtime_error",
                result.stderr.strip() or result.stdout.strip() or "Podman command failed",
                status_code=503,
            )
        return result

    async def _podman(self, *args: str, timeout: float = 30) -> RuntimeCommandResult:
        return await self._podman_limited(*args, timeout=timeout)

    async def _podman_bytes(
        self,
        *args: str,
        stdin: bytes | None = None,
        limit: int,
        timeout: float = 30,
    ) -> tuple[int, bytes, bytes, bool]:
        try:
            process = await asyncio.create_subprocess_exec(
                self.binary,
                *args,
                stdin=asyncio.subprocess.PIPE if stdin is not None else asyncio.subprocess.DEVNULL,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE,
            )
        except FileNotFoundError as exc:
            raise AppError("podman_not_found", "Podman is not installed", status_code=503) from exc

        async def drain(stream: asyncio.StreamReader, max_bytes: int) -> tuple[bytes, bool]:
            kept = bytearray()
            truncated = False
            while chunk := await stream.read(64 * 1024):
                remaining = max_bytes - len(kept)
                if remaining > 0:
                    kept.extend(chunk[:remaining])
                truncated = truncated or len(chunk) > max(remaining, 0)
            return bytes(kept), truncated

        stdout_task = asyncio.create_task(drain(process.stdout, limit))
        stderr_task = asyncio.create_task(drain(process.stderr, min(limit, 64 * 1024)))
        if stdin is not None and process.stdin is not None:
            process.stdin.write(stdin)
            await process.stdin.drain()
            process.stdin.close()
        try:
            await asyncio.wait_for(process.wait(), timeout=timeout)
        except TimeoutError:
            process.kill()
            await process.wait()
            await asyncio.gather(stdout_task, stderr_task)
            raise AppError(
                "workspace_runtime_timeout",
                "Podman file operation timed out",
                status_code=503,
            ) from None
        stdout_result, stderr_result = await asyncio.gather(stdout_task, stderr_task)
        return (
            process.returncode or 0,
            stdout_result[0],
            stderr_result[0],
            stdout_result[1] or stderr_result[1],
        )

    async def _podman_limited(
        self,
        *args: str,
        stdin: bytes | None = None,
        timeout: float = 30,
    ) -> RuntimeCommandResult:
        try:
            process = await asyncio.create_subprocess_exec(
                self.binary,
                *args,
                stdin=asyncio.subprocess.PIPE if stdin is not None else asyncio.subprocess.DEVNULL,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE,
            )
        except FileNotFoundError as exc:
            raise AppError("podman_not_found", "Podman is not installed", status_code=503) from exc

        async def drain(
            stream: asyncio.StreamReader,
            kept: bytearray,
            truncated: list[bool],
        ) -> None:
            while chunk := await stream.read(64 * 1024):
                remaining = self.settings.perry_workspace_max_output_bytes - len(kept)
                if remaining > 0:
                    kept.extend(chunk[:remaining])
                if len(chunk) > remaining:
                    truncated[0] = True

        stdout_kept = bytearray()
        stderr_kept = bytearray()
        stdout_truncated = [False]
        stderr_truncated = [False]
        stdout_task = asyncio.create_task(
            drain(process.stdout, stdout_kept, stdout_truncated)
        )
        stderr_task = asyncio.create_task(
            drain(process.stderr, stderr_kept, stderr_truncated)
        )
        if stdin is not None and process.stdin is not None:
            process.stdin.write(stdin)
            await process.stdin.drain()
            process.stdin.close()

        timed_out = False
        try:
            await asyncio.wait_for(process.wait(), timeout=timeout)
        except TimeoutError:
            timed_out = True
            process.kill()
            await process.wait()
        await _finish_output_tasks(stdout_task, stderr_task)
        return RuntimeCommandResult(
            exit_code=-1 if timed_out else (process.returncode or 0),
            stdout=bytes(stdout_kept).decode(errors="replace"),
            stderr=bytes(stderr_kept).decode(errors="replace"),
            timed_out=timed_out or (process.returncode == 124),
            truncated=stdout_truncated[0] or stderr_truncated[0],
        )


async def _finish_output_tasks(*tasks: asyncio.Task[None]) -> None:
    _, pending = await asyncio.wait(tasks, timeout=1)
    for task in pending:
        task.cancel()
    await asyncio.gather(*tasks, return_exceptions=True)


def _normalize_absolute_path(path: str) -> str:
    if "\x00" in path or not path.startswith("/"):
        raise AppError("invalid_path", "workspace path must be absolute", status_code=400)
    normalized = posixpath.normpath(path)
    if not normalized.startswith("/"):
        raise AppError("invalid_path", "invalid workspace path", status_code=400)
    return normalized


def _normalize_cwd(cwd: str) -> str:
    raw = cwd.strip()
    if not raw:
        return "/workspace"
    if raw.startswith("/"):
        normalized = _normalize_absolute_path(raw)
    else:
        normalized = posixpath.normpath(posixpath.join("/workspace", raw))
    if normalized != "/workspace" and not normalized.startswith("/workspace/"):
        raise AppError("invalid_cwd", "cwd must stay inside /workspace", status_code=400)
    return normalized
