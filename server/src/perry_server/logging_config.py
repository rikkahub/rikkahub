import logging
import re
from typing import Any

_SENSITIVE_RE = re.compile(
    r"(?i)(authorization|bootstrap[_-]?token|device[_-]?token|api[_-]?key|"
    r"secret|password|pepper)\s*[:=]\s*\S+"
)
_BEARER_RE = re.compile(r"(?i)Bearer\s+[A-Za-z0-9\-._~+/]+=*")


class RedactingFilter(logging.Filter):
    def filter(self, record: logging.LogRecord) -> bool:
        if isinstance(record.msg, str):
            record.msg = _redact(record.msg)
        if record.args:
            if isinstance(record.args, dict):
                record.args = {k: _redact_value(v) for k, v in record.args.items()}
            elif isinstance(record.args, tuple):
                record.args = tuple(_redact_value(a) for a in record.args)
        return True


def _redact_value(value: Any) -> Any:
    if isinstance(value, str):
        return _redact(value)
    return value


def _redact(text: str) -> str:
    text = _BEARER_RE.sub("Bearer [REDACTED]", text)
    return _SENSITIVE_RE.sub(r"\1=[REDACTED]", text)


def setup_logging(level: int = logging.INFO) -> None:
    root = logging.getLogger()
    if not root.handlers:
        logging.basicConfig(
            level=level,
            format="%(asctime)s %(levelname)s [%(name)s] %(message)s",
        )
    root.addFilter(RedactingFilter())
    for handler in root.handlers:
        handler.addFilter(RedactingFilter())
