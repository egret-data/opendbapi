---
sidebar_position: 3
---

# Configuration

Server settings, for operators. Set them as Spring properties (`application.yml`, `--name=value`) or as environment
variables: upper-case, with `.` and `-` turned into `_`, e.g. `opendbapi.serving.slow-query-ms` →
`OPENDBAPI_SERVING_SLOW_QUERY_MS`. List values are comma-separated.

## Database and keys

| Environment variable | Default | Meaning |
|---|---|---|
| `OPENDBAPI_DB_URL` | `jdbc:postgresql://localhost:55432/opendbapi` | OpenDBAPI's own PostgreSQL |
| `OPENDBAPI_DB_USER` / `OPENDBAPI_DB_PASSWORD` | `opendbapi_app` / — | App role, subject to row-level security |
| `OPENDBAPI_DB_OWNER` / `OPENDBAPI_DB_OWNER_PASSWORD` | `opendbapi_owner` / — | Owner role, runs the migrations |
| `OPENDBAPI_MASTER_KEY` | — | Base64 of 32 bytes; encrypts stored secrets |
| `OPENDBAPI_BOOTSTRAP_ADMIN_PASSWORD` | random, printed once | Password of the first system admin |
| `OPENDBAPI_SECURE_COOKIES` | `true` in the image | Session cookies only over HTTPS |
| `OPENDBAPI_TRACE_SAMPLING` | `0.1` | Share of requests traced |

The server listens on port 8520. Browser sessions last 8 hours.

## `opendbapi.*`

| Property | Default | Meaning |
|---|---|---|
| `auth.local-enabled` | `true` | Offer local accounts on the sign-in page; system admins can always sign in locally |
| `auth.lockout-attempts` | `5` | Wrong local passwords in a row before the account locks |
| `auth.lockout-duration` | `15m` | How long it stays locked |
| `auth.post-login-redirect` | `/` | Where the browser lands after SSO |
| `serving.context` | `api` | First path segment of every endpoint: `/{context}/{workspace}/…` |
| `serving.url-base` | derived from the request | Public base URL shown in endpoint URLs and API documents |
| `serving.trusted-proxies` | — | IPs or CIDR ranges of reverse proxies whose `X-Forwarded-For` is believed |
| `serving.slow-query-ms` | `1000` | Statements slower than this are logged (SQL only, no values) |
| `serving.docs-ui` | `true` | Serve `/api/{workspace}/docs.html` |
| `datasources.extra-denied-hosts` | — | Host names, `*.suffix` wildcards or CIDR ranges datasources may not reach, besides cloud metadata addresses |
| `datasources.allowed-hosts` | — | When set, the only hosts datasources may reach |
| `access-log.retention-days` | `30` | Days **Activity** keeps calls |
| `alerts.allow-http` | `false` | Allow `http://` webhook URLs |
| `alerts.dedup-window` | `PT5M` | One alert per endpoint and error code within this window |
| `cache.max-bytes` | `134217728` (128 MB) | Memory for cached answers, per instance |
| `publish.require-tests-pass` | `false` | Refuse to publish while an endpoint's tests fail |
| `rate-limit.shared` | `false` (`true` in the Helm chart) | Count rate limits across instances in PostgreSQL |
| `script.groovy-enabled` | `true` | Allow Groovy script endpoints and nodes |
| `script.timeout` | `2s` | Run time of a fragment-mode script |
| `script.full-timeout` | `30s` | Run time of a full-mode script |
| `tokens.ttl` | `PT15M` | Lifetime of client access tokens |
| `ui.read-only` | `false` | Lock every workspace to configuration files |
| `vault.*` | — | See [HashiCorp Vault](../deploy/vault.md) |

## Actuator

`/actuator/health` (with `/liveness` and `/readiness`) and `/actuator/prometheus` are exposed; nothing else is.
