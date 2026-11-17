# OpenDBAPI

Publish SQL as versioned, access-controlled REST APIs, with workspaces for multi-tenancy and
sign-in through local accounts, LDAP or SSO. A clean-room rewrite of the ideas behind DBAPI;
the full design is in [`docs/SPEC.md`](docs/SPEC.md).

> **Status:** P1 roadmap (steps 1–11) done: schema with per-workspace row-level security,
> sign-in (local, LDAP, SSO/OIDC) with group → role mapping, workspaces, members, users,
> identity providers and audit log; the SQL template engine with static checks and
> pagination; datasources (PostgreSQL, MySQL, MariaDB, SQL Server, Oracle) with connection
> tests and a table browser; endpoints with drafts, validation, preview (always rolled
> back), publish, versions and rollback, served at `/api/{workspace}/{path}`; service
> accounts with OAuth2 client-credentials tokens (`POST /oauth2/token`) and per-endpoint or
> per-group grants for private endpoints; per-workspace IP firewall and rate limits; Try-it
> through the real serving pipeline; generated OpenAPI 3.1 and Swagger UI per workspace;
> NDJSON/CSV streaming; Prometheus metrics, OpenTelemetry tracing, slow-query log, access log table
> and an Activity page.
>
> **P2** is in progress; the step list is in [`docs/SPEC.md` §25.2](docs/SPEC.md). Done so far:
> Playwright tests for the main author flow; column masks and per-caller row filters (data policies)
> with service account attributes; parameter rules; webhook alerts (Slack, Teams, Google Chat, JSON)
> for failing endpoints; saved test cases per endpoint, optionally required to pass before publish.

## Layout

```text
backend/            Maven multi-module, Java 21, Spring Boot 3.3
  opendbapi-sql/      SQL templates, expression language, static checks, pagination (plain Java)
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
OPENDBAPI_MASTER_KEY="$(openssl rand -base64 32)" \
OPENDBAPI_BOOTSTRAP_ADMIN_PASSWORD=change-me-please \
  java -jar opendbapi-app/target/opendbapi-app-0.1.0-SNAPSHOT.jar

# 3. Frontend dev server on :5173 (proxies /admin/api and /api to :8520)
cd frontend
npm ci
npm run dev
```

On first start the app creates the system administrator `admin`. Its password is
`OPENDBAPI_BOOTSTRAP_ADMIN_PASSWORD`, or, when that is unset, a random one printed once in the
log. `OPENDBAPI_MASTER_KEY` (base64 of 32 bytes) seals stored secrets such as LDAP bind
passwords; keep it stable, or secrets saved with the old key can no longer be read. Secrets
can also be given as `env:NAME` references instead.

To try LDAP and SSO locally, sign in as `admin` and add under **Administration → Identity
providers**:

| Type | Settings |
|---|---|
| LDAP | URL `ldap://localhost:1389`, *Allow unencrypted* on, service DN `cn=admin,dc=opendbapi,dc=local` / password `admin`, user base `ou=users,dc=opendbapi,dc=local`, user filter `(uid={0})`, group base `ou=groups,dc=opendbapi,dc=local`, group filter `(member={0})` |
| OIDC | Issuer `http://localhost:8180/realms/opendbapi`, client `opendbapi`, secret `opendbapi-dev-secret` |

Then map groups on the provider's **Group mappings** tab, for example `sales-authors` →
workspace *Sales* as Author, and `opendbapi-admins` → System admin.

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
cd frontend && npm run lint && npm run typecheck && npm test && npm run build
```

### End-to-end tests

Playwright drives the main author flow in a browser: datasource, endpoint, preview, publish,
service account, client, grant, Try-it, a real token call, and Activity. It runs against the
stack from [Run locally](#run-locally) (dev services, backend on :8520, `npm run dev` on
:5173) and creates a fresh workspace each run, so it can repeat on the same database.

```bash
cd frontend
npx playwright install chromium   # once
OPENDBAPI_E2E_ADMIN_PASSWORD=change-me-please npm run test:e2e
```

The test datasource defaults to the dev PostgreSQL on `localhost:55432`. Point it elsewhere
with `OPENDBAPI_E2E_DS_HOST`, `_PORT`, `_DATABASE`, `_USER` and `_PASSWORD`, and at another
UI with `OPENDBAPI_E2E_BASE_URL`.

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
