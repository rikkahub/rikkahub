# Deployment notes

## WSL2 development

```bash
cd /home/purbliss/src/haruhome/server   # prefer Linux FS, not /mnt/c
cp .env.example .env
uv sync
uv run alembic upgrade head
uv run uvicorn perry_server.main:app --host 127.0.0.1 --port 8787
```

Android on Windows can reach WSL via localhost forwarding when mirrored networking is enabled,
or via the WSL IP. Do not expose an unauthenticated service on the LAN.

## Ubuntu / Armbian ARM64

1. Create system user `haruhome` (non-root).
2. Install Python 3.12 + uv under that user.
3. Place code under `/opt/haruhome/server`, data under `/opt/haruhome/data`.
4. Copy `deploy/systemd/perry.service.example` to `/etc/systemd/system/perry.service`.
5. Bind Uvicorn to loopback or Tailscale only; put Caddy/Tailscale in front for HTTPS.
6. Keep Monel and MinIO as separate services; Monel must not be public.

## Windows native (optional later)

Use WinSW/NSSM with the same env vars; store config under `%PROGRAMDATA%\Haruhome`.
