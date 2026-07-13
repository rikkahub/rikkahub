# Perry Server

Haruhome personal cloud backend (Phase 1 foundation).

## Requirements

- Python 3.12
- [uv](https://docs.astral.sh/uv/)

Prefer developing on WSL2 Ubuntu with the project and SQLite data on the Linux filesystem
(not `/mnt/c`) to avoid SQLite lock/IO issues.

## Setup

```bash
cd server
cp .env.example .env
# edit PERRY_BOOTSTRAP_TOKEN and PERRY_TOKEN_PEPPER
uv sync
uv run alembic upgrade head
uv run uvicorn perry_server.main:app --host 127.0.0.1 --port 8787
```

## Health

- `GET /health/live` — process up
- `GET /health/ready` — DB reachable (no auth)

## Device bootstrap

```bash
curl -s -X POST http://127.0.0.1:8787/v1/devices/register \
  -H 'Content-Type: application/json' \
  -H 'X-Bootstrap-Token: <PERRY_BOOTSTRAP_TOKEN>' \
  -d '{"name":"pixel-1"}'
```

Response includes a one-time `device_token`. Store it on the device; subsequent calls use:

```http
Authorization: Bearer <device_token>
```

## Tests / lint

```bash
uv run ruff format --check .
uv run ruff check .
uv run mypy src
uv run pytest -q
```

## Layout

```text
src/perry_server/
  api/          HTTP routes
  auth/         token hashing and dependencies
  db/           engine, session, base
  models/       SQLAlchemy models
  schemas/      Pydantic DTOs
  services/     domain services
  config.py
  main.py
```
