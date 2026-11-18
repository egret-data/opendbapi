# Contributing to OpenDBAPI

Thanks for your interest in contributing. This page describes how we work and how to run OpenDBAPI from source.

## Workflow

1. **Open an issue first** for any non-trivial change (a feature, a design change, a bug) and agree on the approach.
   A change to behaviour described in [`docs/SPEC.md`](docs/SPEC.md) updates the spec in the same pull request.
2. **Fork and branch.** Use a descriptive prefix: `feat/`, `fix/`, `docs/`, `chore/`, `refactor/`, `test/`.
3. **Keep pull requests focused.** One logical change per PR. Reference the issue in the PR description with
   `Closes #<number>`, not in commit messages.
4. **Pass CI.** The backend, frontend and docs checks below must pass.
5. **Review.** At least one maintainer approval is required. PRs are squash-merged.

## Documentation is part of the feature

A PR that changes anything a user can see or configure (a UI screen, an API, a configuration key, a permission, a CLI
command) includes the matching page under [`docs/docs/`](docs/docs/):

- Follow [`docs/STYLE.md`](docs/STYLE.md): one page per feature, UI labels exactly as the product shows them.
- UI changes come with updated screenshots, taken by `docs/scripts/screenshots.mjs` from the seeded demo workspace.
- A change to the management API regenerates `openapi.json` and the API reference (see [API contract](#api-contract)).
- English is canonical; the Vietnamese translation of the site may follow in a later PR.

## Commit messages

We follow [Conventional Commits](https://www.conventionalcommits.org/):

```text
feat(endpoints): organize endpoints in folder-style groups
fix(account): serve /account/tokens on reload
docs: describe data policies
```

## Development

Prerequisites: Java 21, Maven 3.9+, Node 24+, Docker.

```bash
# 1. Dependencies: PostgreSQL :55432, OpenLDAP :1389, Keycloak :8180
docker compose -f deploy/dev/docker-compose.yml up -d --wait

# 2. Backend on :8520 (migrates the schema on start)
cd backend
mvn -q install -DskipTests
OPENDBAPI_DB_PASSWORD=app OPENDBAPI_DB_OWNER_PASSWORD=owner \
OPENDBAPI_MASTER_KEY="$(openssl rand -base64 32)" \
OPENDBAPI_BOOTSTRAP_ADMIN_PASSWORD=change-me-please \
  java -jar opendbapi-app/target/opendbapi-app-0.3.1.jar

# 3. Frontend dev server on :5173 (proxies /admin/api and /api to :8520)
cd frontend
npm ci
npm run dev
```

On first start the app creates the system administrator `admin`, with the password in
`OPENDBAPI_BOOTSTRAP_ADMIN_PASSWORD`, or a random one printed once in the log when that is unset.
`OPENDBAPI_MASTER_KEY` (base64 of 32 bytes) seals stored secrets; keep it stable while you work, or secrets saved with
the old key can no longer be read.

### Sign in with LDAP or SSO locally

Sign in as `admin` and add these under **Administration → Identity providers**:

| Type | Settings |
|---|---|
| LDAP | URL `ldap://localhost:1389`, *Allow unencrypted* on, service DN `cn=admin,dc=opendbapi,dc=local` / password `admin`, user base `ou=users,dc=opendbapi,dc=local`, user filter `(uid={0})`, group base `ou=groups,dc=opendbapi,dc=local`, group filter `(member={0})` |
| OIDC | Issuer `http://localhost:8180/realms/opendbapi`, client `opendbapi`, secret `opendbapi-dev-secret` |

Then map groups on the provider's **Group mappings** tab, for example `sales-authors` → workspace *Sales* as Author,
and `opendbapi-admins` → System admin.

Development accounts (local only):

| Service | Account |
|---|---|
| PostgreSQL | `opendbapi_owner` / `owner` (migrations), `opendbapi_app` / `app` (application, subject to row-level security) |
| OpenLDAP `dc=opendbapi,dc=local` | admin `cn=admin,dc=opendbapi,dc=local` / `admin`; users `alice` (group `opendbapi-admins`), `bob` (`sales-authors`), password = username |
| Keycloak realm `opendbapi` | console `admin` / `admin`; users `carol` (`opendbapi-admins`), `dave` (`sales-authors`), password = username; client `opendbapi` / `opendbapi-dev-secret` |

## Checks

```bash
# Backend: unit and Testcontainers integration tests (Docker required)
cd backend && mvn verify

# Frontend
cd frontend && npm run lint && npm run typecheck && npm test && npm run build

# Docs site
cd docs && npm ci && npm run typecheck && npm run build
```

### End-to-end tests

Playwright drives the main author flow in a browser: datasource, endpoint, preview, publish, service account, client,
grant, Try-it, a real token call, and Activity. It runs against the stack from [Development](#development) and creates
a fresh workspace each run, so it can repeat on the same database.

```bash
cd frontend
npx playwright install chromium   # once
OPENDBAPI_E2E_ADMIN_PASSWORD=change-me-please npm run test:e2e
```

The test datasource defaults to the dev PostgreSQL on `localhost:55432`. Point it elsewhere with
`OPENDBAPI_E2E_DS_HOST`, `_PORT`, `_DATABASE`, `_USER` and `_PASSWORD`, and at another UI with
`OPENDBAPI_E2E_BASE_URL`.

### API contract

`openapi.json` is generated from the running backend and checked by `ApplicationIntegrationTest`. After changing the
management API, regenerate it, the frontend client and the docs' API reference:

```bash
cd backend && mvn -pl opendbapi-app -am test -Dtest=ApplicationIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false -Dopenapi.write=true
cd ../frontend && npm run gen:api
cd ../docs && npm run gen:api
```

## Packaging

Run `npm run build` in `frontend/`, then `mvn package` in `backend/`: the app jar serves the UI from `frontend/dist`.
Without a frontend build the jar still runs, API only. `docker build -t opendbapi .` builds the same image that is
published as `ghcr.io/egret-data/opendbapi`.

## Code guidelines

- Write tests for new features and bug fixes (Arrange / Act / Assert).
- Follow existing patterns; prefer readability over cleverness.
- UI text is the English source string; add its Vietnamese translation under `frontend/src/i18n/vi/`. A test fails when
  one is missing.
- Fail fast with specific, contextual errors.
- Never commit secrets or credentials.
- Label Markdown code fences with a language identifier.

## License

By contributing, you agree that your contributions will be licensed under the [Apache License 2.0](LICENSE).
