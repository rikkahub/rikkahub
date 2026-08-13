#!/usr/bin/env python3
import os
import re
import signal
import stat
import sys
import uuid

SUCCESS = (
    b"voice-step.status=ok\n"
    b"voice-step.operation=resolve-binding\n"
    b"voice-step.binding=resolved\n"
)
ERROR = b"voice-step.error=operation failed\n"
IDENTITY = re.compile(r"^([0-9]+):([0-9]+)$")
HANDLED_SIGNALS = {signal.SIGHUP, signal.SIGINT, signal.SIGTERM}


def write_all(fd: int, payload: bytes) -> None:
    offset = 0
    while offset < len(payload):
        written = os.write(fd, payload[offset:])
        if written <= 0:
            raise OSError
        offset += written


def parse_identity(value: str) -> tuple[int, int]:
    match = IDENTITY.fullmatch(value)
    if match is None:
        raise ValueError
    return int(match.group(1)), int(match.group(2))


def canonical_uuid(value: str) -> str:
    parsed = uuid.UUID(value)
    if str(parsed) != value:
        raise ValueError
    return value


def validate_parent_metadata(metadata: os.stat_result, expected: tuple[int, int]) -> None:
    if (
        not stat.S_ISDIR(metadata.st_mode)
        or stat.S_IMODE(metadata.st_mode) != 0o700
        or metadata.st_uid != os.geteuid()
        or (metadata.st_dev, metadata.st_ino) != expected
    ):
        raise OSError


def validate_parent_path(parent: str, expected: tuple[int, int]) -> None:
    metadata = os.lstat(parent)
    validate_parent_metadata(metadata, expected)
    if os.path.realpath(parent) != parent:
        raise OSError


def validate_parent_fd(parent_fd: int, expected: tuple[int, int]) -> None:
    validate_parent_metadata(os.fstat(parent_fd), expected)


def require_absent(parent_fd: int, name: str) -> None:
    try:
        os.stat(name, dir_fd=parent_fd, follow_symlinks=False)
    except FileNotFoundError:
        return
    raise FileExistsError


def publish(arguments: list[str]) -> None:
    if len(arguments) != 3:
        raise ValueError
    destination, identity, conversation = arguments
    if not os.path.isabs(destination) or os.path.normpath(destination) != destination:
        raise ValueError
    parent = os.path.dirname(destination)
    name = os.path.basename(destination)
    if not name or "/" in name:
        raise ValueError
    expected_parent = parse_identity(identity)
    payload = (canonical_uuid(conversation) + "\n").encode("ascii")

    validate_parent_path(parent, expected_parent)
    parent_fd = os.open(
        parent,
        os.O_RDONLY | os.O_DIRECTORY | os.O_CLOEXEC | os.O_NOFOLLOW,
    )
    validate_parent_fd(parent_fd, expected_parent)
    require_absent(parent_fd, name)

    fd = os.open(
        ".",
        os.O_RDWR | os.O_CLOEXEC | os.O_TMPFILE,
        0o600,
        dir_fd=parent_fd,
    )
    os.fchmod(fd, 0o600)
    write_all(fd, payload)
    os.fsync(fd)
    os.lseek(fd, 0, os.SEEK_SET)
    if os.read(fd, len(payload) + 1) != payload:
        raise OSError
    metadata = os.fstat(fd)
    if (
        not stat.S_ISREG(metadata.st_mode)
        or stat.S_IMODE(metadata.st_mode) != 0o600
        or metadata.st_nlink != 0
        or metadata.st_size != len(payload)
    ):
        raise OSError

    validate_parent_fd(parent_fd, expected_parent)
    validate_parent_path(parent, expected_parent)
    require_absent(parent_fd, name)
    signal.signal(signal.SIGHUP, signal.SIG_IGN)
    signal.signal(signal.SIGINT, signal.SIG_IGN)
    signal.signal(signal.SIGTERM, signal.SIG_IGN)
    os.link(
        f"/proc/self/fd/{fd}",
        name,
        dst_dir_fd=parent_fd,
        follow_symlinks=True,
    )
    try:
        write_all(1, SUCCESS)
    except BaseException:
        pass
    os._exit(0)


def fail_signal(_signum: int, _frame: object) -> None:
    raise OSError


def exit_failure() -> None:
    try:
        write_all(2, ERROR)
    except BaseException:
        pass
    os._exit(1)


def main() -> None:
    try:
        for handled_signal in HANDLED_SIGNALS:
            signal.signal(handled_signal, fail_signal)
        signal.pthread_sigmask(signal.SIG_UNBLOCK, HANDLED_SIGNALS)
        publish(sys.argv[1:])
    except BaseException:
        exit_failure()


if __name__ == "__main__":
    main()
