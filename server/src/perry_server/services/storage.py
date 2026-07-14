from __future__ import annotations

import re
from dataclasses import dataclass
from io import BytesIO
from typing import Protocol
from uuid import UUID

from perry_server.config import Settings
from perry_server.errors import AppError


@dataclass(slots=True, frozen=True)
class ObjectHead:
    size_bytes: int
    etag: str | None = None
    content_type: str | None = None


class ObjectStorage(Protocol):
    def is_configured(self) -> bool: ...

    def probe(self) -> tuple[str, str | None]:
        """Return (status, detail) for server-info components."""
        ...

    def ensure_bucket(self) -> None: ...

    def build_object_key(
        self,
        *,
        user_id: UUID,
        file_id: UUID,
        display_name: str,
    ) -> str: ...

    def head_object(self, object_key: str) -> ObjectHead | None: ...

    def put_object(self, object_key: str, data: bytes, *, content_type: str) -> None: ...

    def get_object_bytes(self, object_key: str) -> bytes | None: ...

    def delete_object(self, object_key: str) -> None: ...


_SAFE_NAME = re.compile(r"[^A-Za-z0-9._-]+")


def sanitize_object_name(display_name: str) -> str:
    base = display_name.rsplit("/", 1)[-1].rsplit("\\", 1)[-1].strip() or "file"
    cleaned = _SAFE_NAME.sub("_", base)
    return cleaned[:180] or "file"


class InMemoryObjectStorage:
    """Test/dev storage that keeps bytes in process memory."""

    def __init__(self, *, public_base_url: str = "http://test") -> None:
        self._objects: dict[str, tuple[bytes, str]] = {}
        self._public_base_url = public_base_url.rstrip("/")
        self._bucket_ready = False

    def is_configured(self) -> bool:
        return True

    def probe(self) -> tuple[str, str | None]:
        return "ok", "in-memory storage (proxy mode)"

    def ensure_bucket(self) -> None:
        self._bucket_ready = True

    def build_object_key(
        self,
        *,
        user_id: UUID,
        file_id: UUID,
        display_name: str,
    ) -> str:
        name = sanitize_object_name(display_name)
        return f"users/{user_id}/files/{file_id}/{name}"

    def head_object(self, object_key: str) -> ObjectHead | None:
        item = self._objects.get(object_key)
        if item is None:
            return None
        data, content_type = item
        return ObjectHead(size_bytes=len(data), content_type=content_type)

    def put_object(self, object_key: str, data: bytes, *, content_type: str) -> None:
        self._objects[object_key] = (data, content_type)

    def get_object_bytes(self, object_key: str) -> bytes | None:
        item = self._objects.get(object_key)
        return None if item is None else item[0]

    def delete_object(self, object_key: str) -> None:
        self._objects.pop(object_key, None)


class MinioObjectStorage:
    """Server-side MinIO client. Android never talks to MinIO directly."""

    def __init__(self, settings: Settings) -> None:
        if not settings.minio_endpoint or not settings.minio_access_key or not settings.minio_secret_key:
            raise AppError("minio_not_configured", "MinIO is not configured", status_code=503)
        from minio import Minio

        self._bucket = settings.minio_bucket
        self._client = Minio(
            settings.minio_endpoint,
            access_key=settings.minio_access_key,
            secret_key=settings.minio_secret_key,
            secure=settings.minio_secure,
        )

    def is_configured(self) -> bool:
        return True

    def probe(self) -> tuple[str, str | None]:
        try:
            self._client.bucket_exists(self._bucket)
            return "ok", "minio reachable via perry proxy"
        except Exception as exc:  # noqa: BLE001
            return "degraded", str(exc)

    def ensure_bucket(self) -> None:
        if not self._client.bucket_exists(self._bucket):
            self._client.make_bucket(self._bucket)

    def build_object_key(
        self,
        *,
        user_id: UUID,
        file_id: UUID,
        display_name: str,
    ) -> str:
        name = sanitize_object_name(display_name)
        return f"users/{user_id}/files/{file_id}/{name}"

    def head_object(self, object_key: str) -> ObjectHead | None:
        try:
            info = self._client.stat_object(self._bucket, object_key)
        except Exception:  # noqa: BLE001
            return None
        return ObjectHead(
            size_bytes=int(info.size or 0),
            etag=getattr(info, "etag", None),
            content_type=getattr(info, "content_type", None),
        )

    def put_object(self, object_key: str, data: bytes, *, content_type: str) -> None:
        self._client.put_object(
            self._bucket,
            object_key,
            BytesIO(data),
            length=len(data),
            content_type=content_type,
        )

    def get_object_bytes(self, object_key: str) -> bytes | None:
        try:
            response = self._client.get_object(self._bucket, object_key)
        except Exception:  # noqa: BLE001
            return None
        try:
            return response.read()
        finally:
            response.close()
            response.release_conn()

    def delete_object(self, object_key: str) -> None:
        try:
            self._client.remove_object(self._bucket, object_key)
        except Exception:  # noqa: BLE001
            pass


class UnconfiguredObjectStorage:
    def is_configured(self) -> bool:
        return False

    def probe(self) -> tuple[str, str | None]:
        return "not_configured", "minio not configured"

    def ensure_bucket(self) -> None:
        raise AppError("minio_not_configured", "MinIO is not configured", status_code=503)

    def build_object_key(
        self,
        *,
        user_id: UUID,
        file_id: UUID,
        display_name: str,
    ) -> str:
        name = sanitize_object_name(display_name)
        return f"users/{user_id}/files/{file_id}/{name}"

    def head_object(self, object_key: str) -> ObjectHead | None:
        del object_key
        raise AppError("minio_not_configured", "MinIO is not configured", status_code=503)

    def put_object(self, object_key: str, data: bytes, *, content_type: str) -> None:
        del object_key, data, content_type
        raise AppError("minio_not_configured", "MinIO is not configured", status_code=503)

    def get_object_bytes(self, object_key: str) -> bytes | None:
        del object_key
        raise AppError("minio_not_configured", "MinIO is not configured", status_code=503)

    def delete_object(self, object_key: str) -> None:
        del object_key
        raise AppError("minio_not_configured", "MinIO is not configured", status_code=503)


def create_object_storage(settings: Settings) -> ObjectStorage:
    if settings.perry_env == "test":
        return InMemoryObjectStorage(public_base_url=settings.perry_public_base_url)
    if settings.minio_endpoint and settings.minio_access_key and settings.minio_secret_key:
        return MinioObjectStorage(settings)
    return UnconfiguredObjectStorage()
