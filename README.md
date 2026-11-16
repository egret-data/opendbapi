# OpenDBAPI

Publish SQL as versioned, access-controlled REST APIs, with workspaces for multi-tenancy and
sign-in through local accounts, LDAP or SSO. A clean-room rewrite of the ideas behind DBAPI;
the full design is in [`docs/SPEC.md`](docs/SPEC.md).

> **Status:** roadmap step 1 (skeleton). The schema, workspace isolation, app shell and
> build pipeline are in place; feature areas show a "Roadmap step N" placeholder.

## Layout

```text
backend/            Maven multi-module, Java 21, Spring Boot 3.3
  opendbapi-core/     schema (Flyway), workspace isolation, configuration
  opendbapi-admin/    management API under /admin/api
  opendbapi-serving/  published endpoints under /{context}/{workspace}/**
  opendbapi-app/      standalone Spring Boot app (bundles the UI)
frontend/           React 19 + Vite + Tailwind v4 (shell and primitives from Egret)
deploy/dev/         docker compose: PostgreSQL, OpenLDAP, Keycloak
openapi.json        management API contract; frontend client is generated from it
docs/SPEC.md        specification
```

## Prerequisites

Java 21, Maven 3.9+, Node 24+, Docker.

## Run locally

```bash
# 1. Dependencies (PostgreSQL :55432, LDAP :1389, Keycloak :8180)
docker compose -f deploy/dev/docker-compose.yml up -d --wait

# 2. Backend on :8520 (migrates the schema on start)
cd backend
mvn -q install -DskipTests
OPENDBAPI_DB_PASSWORD=app OPENDBAPI_DB_OWNER_PASSWORD=owner \
  java -jar opendbapi-app/target/opendbapi-app-0.1.0-SNAPSHOT.jar

# 3. Frontend dev server on :5173 (proxies /admin/api and /api to :8520)
cd frontend
npm ci
npm run dev
```

Development credentials (local only):

| Service | Account |
|---|---|
| PostgreSQL | `opendbapi_owner` / `owner` (migrations), `opendbapi_app` / `app` (application, subject to RLS) |
| OpenLDAP `dc=opendbapi,dc=local` | admin `cn=admin,dc=opendbapi,dc=local` / `admin`; users `alice` (group `opendbapi-admins`), `bob` (`sales-authors`), password = username |
| Keycloak realm `opendbapi` | console `admin` / `admin`; users `carol` (`opendbapi-admins`), `dave` (`sales-authors`), password = username; client `opendbapi` / `opendbapi-dev-secret` |

## Checks

```bash
# Backend: unit + Testcontainers integration tests (Docker required)
cd backend && mvn verify

# Frontend gate
cd frontend && npm run lint && npx tsc -b && npm test && npm run build
```

### API contract

`openapi.json` is generated from the running backend and checked by
`ApplicationIntegrationTest`. After changing the management API:

```bash
cd backend && mvn -pl opendbapi-app -am test -Dtest=ApplicationIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false -Dopenapi.write=true
cd ../frontend && npm run gen:api
```

## Packaging

`npm run build` in `frontend/`, then `mvn package` in `backend/`: the app jar serves the UI
from `frontend/dist`. Without a frontend build the jar still runs, API only.

## License

[Apache License 2.0](LICENSE). The UI frame and shared components under `frontend/` derive
from [Egret](https://github.com/egret-data/platform) (Apache-2.0); see [`NOTICE`](NOTICE).
