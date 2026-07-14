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
uv sync
./scripts/install-perry.sh   # installs global `perry` → ~/.local/bin/perry
perry init                   # create .env + random bootstrap token / pepper
perry start                  # background (pid/log under data/)
perry                        # admin TUI (wipe / bootstrap / start-stop)
```

Manual (without CLI):

```bash
cp .env.example .env
# edit PERRY_BOOTSTRAP_TOKEN and PERRY_TOKEN_PEPPER
uv run alembic upgrade head
uv run uvicorn perry_server.main:app --host 127.0.0.1 --port 8787
```

### `perry` CLI

| Command | Description |
|---------|-------------|
| `perry` / `perry tui` | Interactive admin TUI |
| `perry start` | Start server in background |
| `perry start -f` | Foreground |
| `perry stop` / `restart` | Stop / restart |
| `perry status` | Process + DB counts |
| `perry logs [-f]` | Tail `data/perry.log` |
| `perry init` | First-time `.env` + secrets |
| `perry wipe` | Clear sync content, **keep devices** |
| `perry wipe all` | Clear everything including devices |
| `perry bootstrap --show` | Print bootstrap token |
| `perry bootstrap --regen` | New bootstrap token (devices keep working) |

`PERRY_HOME` is set by `install-perry.sh` to this server tree.

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

## Sync (Phase 2)

```http
GET  /v1/sync/bootstrap
GET  /v1/sync/changes?cursor=0&limit=100
POST /v1/sync/mutations
```

First supported entity type: `setting` (key = `entity_id`, payload `{ "value": ... }`).
Mutations are idempotent via `mutation_id` receipts; conflicts use optimistic `base_revision`.

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
