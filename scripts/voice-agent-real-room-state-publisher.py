#!/usr/bin/env python3
"""Private one-shot publisher for the real-room start state."""

import json
import os
import re
import signal
import stat
import sys


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


def parse_identity(value: str) -> tuple[int, int]:
    match = IDENTITY.fullmatch(value)
    if match is None:
        raise ValueError
    return int(match.group(1)), int(match.group(2))


def publish(arguments: list[str]) -> None:
    if len(arguments) != 16:
        raise ValueError
    (
        destination,
        expected_state_parent_identity,
        diagnostic_parent_identity,
        diagnostic_name,
        mdev_owner_hash,
        package,
        android_user_id,
        package_uid,
        conversation,
        run_hash,
        comparison_hash,
        token,
        fixture_parent_identity,
        fixture_directory_identity,
        fixture_ownership_nonce,
        trace,
    ) = arguments
    expected_state_parent = parse_identity(expected_state_parent_identity)
    if diagnostic_parent_identity == "none" and diagnostic_name == "none":
        diagnostic_parent = None
    else:
        diagnostic_parent = parse_identity(diagnostic_parent_identity)
        if not diagnostic_name or diagnostic_name in {".", ".."}:
            raise ValueError
    if (
        not destination
        or not os.path.isabs(destination)
        or os.path.normpath(destination) != destination
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
    parent_identity = (parent_metadata.st_dev, parent_metadata.st_ino)
    if (
        not stat.S_ISDIR(parent_metadata.st_mode)
        or parent_identity != expected_state_parent
        or (diagnostic_parent is not None and
            (parent_identity, name) == (diagnostic_parent, diagnostic_name))
    ):
        raise OSError
    payload = {
        "schemaVersion": 3,
        "mdevOwnerHash": mdev_owner_hash,
        "package": package,
        "androidUserId": int(android_user_id),
        "packageUid": int(package_uid),
        "conversationId": conversation,
        "runHash": run_hash,
        "comparisonHash": comparison_hash,
        "fixtureToken": token,
        "fixtureParentIdentity": fixture_parent_identity,
        "fixtureDirectoryIdentity": fixture_directory_identity,
        "fixtureOwnershipNonce": fixture_ownership_nonce,
        "traceId": trace,
        "transport": "livekit_experimental",
    }
    encoded = (json.dumps(payload, separators=(",", ":")) + "\n").encode("utf-8")
    unnamed_fd = os.open(
        ".",
        os.O_RDWR | os.O_TMPFILE | os.O_CLOEXEC,
        0o600,
        dir_fd=parent_fd,
    )
    os.fchmod(unnamed_fd, 0o600)
    write_all(unnamed_fd, encoded)
    os.fsync(unnamed_fd)
    metadata = os.fstat(unnamed_fd)
    if (
        not stat.S_ISREG(metadata.st_mode)
        or stat.S_IMODE(metadata.st_mode) != 0o600
        or metadata.st_nlink != 0
        or metadata.st_size != len(encoded)
        or read_all(unnamed_fd) != encoded
    ):
        raise OSError
    for handled_signal in (signal.SIGHUP, signal.SIGINT, signal.SIGTERM):
        signal.signal(handled_signal, signal.SIG_IGN)
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
    except Exception:
        os._exit(1)


if __name__ == "__main__":
    main()
