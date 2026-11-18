---
sidebar_position: 1
---

# Deploy with Docker

OpenDBAPI is one image, `ghcr.io/egret-data/opendbapi`, that serves the management UI, the management API and the
published endpoints on port 8520. It stores everything in one PostgreSQL database.

## Prepare PostgreSQL

OpenDBAPI needs PostgreSQL 14 or later for its own data (users, workspaces, endpoints, activity). This is separate from the
databases your endpoints query. Create the database and two roles: an **owner** that runs the schema migrations, and an **app** role
that the server uses at run time, subject to row-level security.

```sql
CREATE ROLE opendbapi_owner LOGIN PASSWORD 'change-me-owner';
CREATE ROLE opendbapi_app   LOGIN PASSWORD 'change-me-app' NOBYPASSRLS;
CREATE DATABASE opendbapi OWNER opendbapi_owner;
```

OpenDBAPI creates and upgrades its schema (`opendbapi`) on start, as the owner.

## Generate a master key

The master key encrypts stored secrets: datasource passwords, signing keys, identity provider secrets.

```bash
openssl rand -base64 32
```

:::warning
Keep the master key safe and stable. Secrets stored with one key cannot be read with another, and anyone with the key and
a database backup can read them.
:::

## Run

```bash
docker run -d --name opendbapi -p 8520:8520 \
  -e OPENDBAPI_DB_URL=jdbc:postgresql://db.example.com:5432/opendbapi \
  -e OPENDBAPI_DB_PASSWORD=change-me-app \
  -e OPENDBAPI_DB_OWNER_PASSWORD=change-me-owner \
  -e OPENDBAPI_MASTER_KEY=... \
  -e OPENDBAPI_BOOTSTRAP_ADMIN_PASSWORD=... \
  ghcr.io/egret-data/opendbapi:0.3.1
```

| Variable | Meaning |
|---|---|
| `OPENDBAPI_DB_URL` | JDBC URL of OpenDBAPI's own database |
| `OPENDBAPI_DB_USER` / `OPENDBAPI_DB_PASSWORD` | The app role (default user `opendbapi_app`) |
| `OPENDBAPI_DB_OWNER` / `OPENDBAPI_DB_OWNER_PASSWORD` | The owner role (default user `opendbapi_owner`) |
| `OPENDBAPI_MASTER_KEY` | Base64 of 32 random bytes |
| `OPENDBAPI_BOOTSTRAP_ADMIN_PASSWORD` | Password of the `admin` user created on first start. When unset, a random one is printed once in the log |
| `OPENDBAPI_SECURE_COOKIES` | `true` in the image: session cookies need HTTPS. Set `false` only for plain-HTTP trials |

Every other setting can be given as an environment variable too; see [Configuration](../reference/configuration.md).

## Put it behind HTTPS

Run OpenDBAPI behind a reverse proxy or load balancer that terminates TLS. Tell it which proxies to trust, so it reads
the caller's address from `X-Forwarded-For`, and the public address to show in endpoint URLs:

```bash
-e OPENDBAPI_SERVING_TRUSTED_PROXIES=10.0.0.0/8
-e OPENDBAPI_SERVING_URL_BASE=https://opendbapi.example.com
```

When `url-base` is empty, OpenDBAPI derives the address from each request.

## Health and metrics

| Path | Use |
|---|---|
| `/actuator/health/liveness` | Liveness probe |
| `/actuator/health/readiness` | Readiness probe (the image's `HEALTHCHECK` uses it) |
| `/actuator/prometheus` | Prometheus metrics; see [Operations](./operations.md) |

The image runs as the non-root user `10001` and can run with a read-only root filesystem.
