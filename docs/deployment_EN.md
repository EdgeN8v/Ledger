# Deployment

<p align="center"><a href="deployment.md">简体中文</a> | <b>English</b></p>

The backend is a plain Docker Compose project (API + Postgres + an optional backup container). A NAS, a Raspberry Pi, a router or a VPS will do.

## 1. Start the services

```bash
git clone https://github.com/EdgeN8v/Ledger.git
cd Ledger
cp .env.example .env
```

Edit `.env`:

```ini
POSTGRES_PASSWORD=your own database password
JWT_SECRET=a long random string
# REGISTRATION_OPEN=true     # only while you create the family accounts
```

Generate a secret: `python -c "import secrets; print(secrets.token_urlsafe(48))"`.

```bash
docker compose up -d --build
curl http://localhost:8000/health         # {"status":"ok"}
```

The container refuses to start without a real `JWT_SECRET`; that is deliberate.

## 2. Create the accounts

Registration is closed by default. To create the first accounts:

1. Add `REGISTRATION_OPEN=true` to `.env` and run `docker compose up -d`.
2. Register yourself in the app; family members use "join ledger" with the invite code (shown on the Me tab).
3. Remove the line and run `docker compose up -d` again: registration is closed.

## 3. Reaching the server from the phone

From safest to least safe:

| Option | Notes |
|---|---|
| Home LAN only | Simplest and safest. Use `http://<nas-lan-ip>:8000/`. Sync only happens on your home WiFi; captures are queued on the phone and uploaded when you get home. |
| A VPN back home | Recommended if you also want it away from home. Run WireGuard or Tailscale on the NAS and the phone and use the VPN address. Nothing needs to be opened on the router. |
| Reverse proxy + HTTPS on the internet | Not recommended. If you must, put Caddy / Nginx with HTTPS and access limits in front. **Never forward port 8000 directly.** |

## 4. Backups

The `backup` service in `docker-compose.yml` dumps the whole database to `./backups/ledger-<date>.sql.gz` every night (03:00 UTC) and keeps the newest 14. Copy `backups/` to another machine or disk as well.

**Restore** (into a fresh, empty database):

```bash
zcat backups/ledger-XXXX.sql.gz | docker compose exec -T db psql -U ledger -d ledger
```

## 5. Upgrading

```bash
git pull
docker compose up -d --build
```

New tables are created on start-up. When a release adds a column to an existing table, its notes include an `ALTER TABLE ... ADD COLUMN IF NOT EXISTS` statement to run once (additive only, no data is lost).

## 6. Phone permissions

Notification access, SMS permission and the Xiaomi battery / autostart settings are described in the [README](../README_EN.md#permissions-on-the-phone).
