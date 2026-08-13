#!/usr/bin/env python3
import os
import sqlite3
import stat
import sys
import uuid
from pathlib import Path
from urllib.parse import quote

MAX_WINDOW_MS = 1_800_000
MAX_EPOCH_MS = 9_223_372_036_854_775_807
MAIN_MIN_BYTES = 512
MAIN_MAX_BYTES = 64 * 1024 * 1024
WAL_MIN_BYTES = 32
WAL_MAX_BYTES = 64 * 1024 * 1024
AGGREGATE_MAX_BYTES = 128 * 1024 * 1024


def parse_epoch_ms(value: str) -> int:
    if not value.isascii() or not value.isdecimal():
        raise ValueError
    parsed = int(value, 10)
    if parsed > MAX_EPOCH_MS:
        raise ValueError
    return parsed


def canonical_uuid(value: object) -> str:
    if not isinstance(value, str):
        raise ValueError
    parsed = uuid.UUID(value)
    if str(parsed) != value:
        raise ValueError
    return value


def validate_regular(path: Path, minimum: int, maximum: int) -> int:
    metadata = path.lstat()
    if not stat.S_ISREG(metadata.st_mode) or metadata.st_nlink != 1:
        raise ValueError
    if metadata.st_size < minimum or metadata.st_size > maximum:
        raise ValueError
    return metadata.st_size


def validate_path(path: Path) -> None:
    value = os.fspath(path)
    if not path.is_absolute() or os.path.normpath(value) != value:
        raise ValueError


def validate_parent(path: Path) -> None:
    parent = path.parent
    metadata = parent.lstat()
    if (
        not stat.S_ISDIR(metadata.st_mode)
        or stat.S_IMODE(metadata.st_mode) != 0o700
        or metadata.st_uid != os.geteuid()
        or os.path.realpath(parent) != os.fspath(parent)
    ):
        raise ValueError


def select(arguments: list[str]) -> str:
    if len(arguments) != 4:
        raise ValueError
    main_value, wal_value, after_value, before_value = arguments
    main_path = Path(main_value)
    validate_path(main_path)
    validate_parent(main_path)
    created_after = parse_epoch_ms(after_value)
    created_before = parse_epoch_ms(before_value)
    if (
        created_before <= created_after
        or created_before - created_after > MAX_WINDOW_MS
    ):
        raise ValueError

    main_size = validate_regular(main_path, MAIN_MIN_BYTES, MAIN_MAX_BYTES)
    uri_suffix = "?mode=ro&immutable=1"
    if wal_value == "-":
        if os.path.lexists(os.fspath(main_path) + "-wal"):
            raise ValueError
    else:
        wal_path = Path(wal_value)
        validate_path(wal_path)
        if wal_path != Path(os.fspath(main_path) + "-wal"):
            raise ValueError
        if wal_path.parent != main_path.parent:
            raise ValueError
        validate_parent(wal_path)
        wal_size = validate_regular(wal_path, WAL_MIN_BYTES, WAL_MAX_BYTES)
        if main_size + wal_size > AGGREGATE_MAX_BYTES:
            raise ValueError
        uri_suffix = "?mode=ro"

    connection = None
    try:
        uri = "file:" + quote(os.fspath(main_path), safe="/") + uri_suffix
        connection = sqlite3.connect(uri, uri=True)
        connection.execute("PRAGMA query_only=ON")
        rows = connection.execute(
            "SELECT id, create_at "
            "FROM ConversationEntity "
            "WHERE create_at >= ? AND create_at < ?",
            (created_after, created_before),
        )
        selected = None
        selected_time = None
        for identifier, created_at in rows:
            canonical = canonical_uuid(identifier)
            if isinstance(created_at, bool) or not isinstance(created_at, int):
                raise ValueError
            if selected_time is None or created_at > selected_time:
                selected = canonical
                selected_time = created_at
            elif created_at == selected_time:
                raise ValueError
        if selected is None:
            raise ValueError
        return selected
    finally:
        if connection is not None:
            connection.close()


def write_selected(value: str) -> None:
    payload = (value + "\n").encode("ascii")
    offset = 0
    while offset < len(payload):
        written = os.write(1, payload[offset:])
        if written <= 0:
            raise OSError
        offset += written


def main() -> None:
    write_selected(select(sys.argv[1:]))


if __name__ == "__main__":
    try:
        main()
    except (OSError, OverflowError, ValueError, sqlite3.Error):
        os._exit(1)
