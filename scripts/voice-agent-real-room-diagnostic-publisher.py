#!/usr/bin/env python3
import os
import re
import stat
import sys

TOKEN = re.compile(r"^[a-z][a-z0-9-]{0,63}$")
CHILD = re.compile(r"^(?:none|[0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])$")
IDENTITY = re.compile(r"^([0-9]+):([0-9]+)$")


def write_all(descriptor: int, payload: bytes) -> None:
    offset = 0
    while offset < len(payload):
        written = os.write(descriptor, payload[offset:])
        if written <= 0:
            raise OSError
        offset += written


def read_all(descriptor: int) -> bytes:
    os.lseek(descriptor, 0, os.SEEK_SET)
    chunks = []
    while True:
        chunk = os.read(descriptor, 4096)
        if not chunk:
            return b"".join(chunks)
        chunks.append(chunk)


def publish(arguments: list[str]) -> None:
    if len(arguments) != 7:
        raise ValueError
    destination, expected_identity, operation, stage, category, child, cleanup = arguments
    match = IDENTITY.fullmatch(expected_identity)
    if match is None:
        raise ValueError
    expected_parent = (int(match.group(1)), int(match.group(2)))
    if (
        not os.path.isabs(destination)
        or os.path.normpath(destination) != destination
        or operation != "start"
        or TOKEN.fullmatch(operation) is None
        or TOKEN.fullmatch(stage) is None
        or TOKEN.fullmatch(category) is None
        or CHILD.fullmatch(child) is None
        or cleanup not in {"complete", "failed"}
    ):
        raise ValueError
    parent = os.path.dirname(destination)
    name = os.path.basename(destination)
    if not name or name in {".", ".."} or os.path.realpath(parent) != parent:
        raise ValueError
    parent_fd = os.open(
        parent,
        os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC,
    )
    parent_metadata = os.fstat(parent_fd)
    if (
        not stat.S_ISDIR(parent_metadata.st_mode)
        or stat.S_IMODE(parent_metadata.st_mode) != 0o700
        or parent_metadata.st_uid != os.geteuid()
        or (parent_metadata.st_dev, parent_metadata.st_ino) != expected_parent
    ):
        raise OSError
    payload = (
        "version=1\n"
        f"operation={operation}\n"
        f"stage={stage}\n"
        "outcome=failure\n"
        f"error_category={category}\n"
        f"child_exit_status={child}\n"
        f"cleanup={cleanup}\n"
    ).encode("ascii")
    unnamed_fd = os.open(
        ".",
        os.O_RDWR | os.O_TMPFILE | os.O_CLOEXEC,
        0o600,
        dir_fd=parent_fd,
    )
    os.fchmod(unnamed_fd, 0o600)
    write_all(unnamed_fd, payload)
    os.fsync(unnamed_fd)
    metadata = os.fstat(unnamed_fd)
    if (
        not stat.S_ISREG(metadata.st_mode)
        or stat.S_IMODE(metadata.st_mode) != 0o600
        or metadata.st_nlink != 0
        or read_all(unnamed_fd) != payload
    ):
        raise OSError
    os.link(
        f"/proc/self/fd/{unnamed_fd}",
        name,
        dst_dir_fd=parent_fd,
        follow_symlinks=True,
    )
    os._exit(0)


def main() -> None:
    try:
        publish(sys.argv[1:])
    except (OSError, UnicodeError, ValueError):
        os._exit(1)


if __name__ == "__main__":
    main()
