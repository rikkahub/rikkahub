#!/usr/bin/env python3
import os
import signal
import sys

ERROR = b"voice-step.error=operation failed\n"
MARKER = "VOICE_STEP_RESOLVE_SIGNAL_MASKED"
HANDLED_SIGNALS = {signal.SIGHUP, signal.SIGINT, signal.SIGTERM}


def write_error() -> None:
    try:
        offset = 0
        while offset < len(ERROR):
            written = os.write(2, ERROR[offset:])
            if written <= 0:
                raise OSError
            offset += written
    except BaseException:
        pass


def main() -> None:
    try:
        signal.pthread_sigmask(signal.SIG_BLOCK, HANDLED_SIGNALS)
        if len(sys.argv) < 2:
            raise ValueError
        helper = sys.argv[1]
        if not os.path.isabs(helper) or os.path.normpath(helper) != helper:
            raise ValueError
        environment = os.environ.copy()
        environment[MARKER] = "1"
        os.execve(helper, [helper, *sys.argv[2:]], environment)
    except BaseException:
        write_error()
        os._exit(1)


if __name__ == "__main__":
    main()
