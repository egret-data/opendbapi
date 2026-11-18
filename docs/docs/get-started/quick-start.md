---
sidebar_position: 1
---

# Quick start

Run OpenDBAPI and a small sample shop database on your machine with Docker Compose, then sign in.

You need Docker with the Compose plugin and a clone of the repository:

```bash
git clone https://github.com/egret-data/opendbapi.git
cd opendbapi
```

## Start OpenDBAPI

```bash
docker compose -f deploy/quickstart/docker-compose.yml up -d
```

This starts two containers:

- `postgres`: PostgreSQL 16, which holds OpenDBAPI's own data (users, workspaces, endpoints, activity).
- `opendbapi`: the `ghcr.io/egret-data/opendbapi:0.3.1` image, which serves the management UI and the published
  endpoints on port 8520.

OpenDBAPI creates its schema on first start. Wait until it reports ready:

```bash
curl -s http://localhost:8520/actuator/health/readiness
```

```json
{"status":"UP"}
```

## Load the sample data

The quick start ships a small shop (6 customers, 120 orders) and a read-only database login, `shop_reader`, for
OpenDBAPI to query it with:

```bash
docker compose -f deploy/quickstart/docker-compose.yml exec -T postgres \
  psql -U postgres -d opendbapi < deploy/quickstart/sample-data.sql
```

## Sign in

Open [http://localhost:8520](http://localhost:8520) and sign in as `admin` with the password `admin-change-me`.

![The sign-in page with Username and Password fields](/img/get-started/login.png)

:::warning
The quick start uses fixed, published passwords and a published master key. Use it only to try OpenDBAPI. For anything
else, follow [Deploy with Docker](../deploy/docker.md).
:::

## Next

- [Publish your first endpoint](./first-endpoint.md) on the sample shop.
- Or seed a complete demo workspace (endpoints, a workflow, a service account, a masking policy) with the script the
  docs' screenshots are taken from:

  ```bash
  cd docs && npm ci && node scripts/seed-demo.mjs
  ```

## Stop and clean up

```bash
docker compose -f deploy/quickstart/docker-compose.yml down      # keep the data
docker compose -f deploy/quickstart/docker-compose.yml down -v   # remove it too
```
