---
sidebar_position: 2
---

# Errors

Published endpoints answer errors as RFC 9457 problems (`Content-Type: application/problem+json`) with a stable `code`
and the `requestId` of the call:

```json
{
  "type": "about:blank",
  "title": "Bad Request",
  "status": 400,
  "detail": "Parameter 'tier' must be one of gold, silver, bronze.",
  "code": "invalid_param",
  "requestId": "4f1c9f0e-3a57-4d07-9a8e-2a1f1b0b6c11"
}
```

Program against `status` and `code`; `detail` is for people and may change. Server errors (5xx) keep their details in the
server log and in **Activity**, found by `requestId`.

## Calling endpoints

| Status | Code | Meaning |
|---|---|---|
| 400 | `invalid_param` | A parameter is missing, has the wrong type, or breaks its constraints |
| 400 | `rule_failed` | A [parameter rule](../endpoints/settings.md#parameter-rules) does not hold; `detail` is the rule's message |
| 400 | `invalid_cursor` | The `after` cursor is not one this endpoint issued |
| 400 | `invalid_body` | The JSON body cannot be read |
| 400 | `bad_request` | A full-mode script called `badRequest(…)` |
| 401 | `unauthenticated` | A private endpoint was called without a token (`WWW-Authenticate: Bearer realm="opendbapi"`) |
| 401 | `invalid_token` | The token is malformed, expired, or its client was revoked or disabled |
| 401 | `bad_signature` | The client must [sign requests](../access/request-security.md) and the signature is missing, stale, wrong or replayed |
| 403 | `not_granted` | The service account has no grant on this endpoint or its group |
| 403 | `ip_blocked` | The workspace [firewall](../workspace/security.md#firewall) refuses the caller's address |
| 403 | `missing_attribute` | A row filter needs an attribute the service account does not have |
| 403 | `row_filter_needs_client` | A row filter applies, but the call has no client |
| 403 | `write_in_get` | A `GET` endpoint tried to change data |
| 403 | `read_only_datasource` | A query tried to change data on a read-only datasource |
| 404 | `not_found` | No published endpoint at this method and path, or a **Single object** endpoint found no row |
| 406 | `not_acceptable` | The requested format is not allowed (streaming off, or an encrypted client asked for a stream) |
| 413 | `body_too_large` | The body is larger than 1 MB |
| 415 | `unsupported_media_type` | The body is not JSON |
| 429 | `rate_limited` | A rate limit is exceeded; wait `Retry-After` seconds |
| 500 | `query_failed` | The database refused the query |
| 500 | `script_failed` | A Groovy script failed |
| 500 | `workflow_failed` | A workflow node failed, or endpoints called each other too deep |
| 500 | `template_failed` | The SQL template could not be rendered with these values |
| 500 | `row_filter_failed` / `keyset_failed` | The filtered or paged query could not be built |
| 503 | `datasource_missing` | The endpoint's datasource was deleted |
| 503 | `endpoint_unavailable` | The endpoint cannot answer right now |
| 504 | `query_timeout` | The query took longer than the endpoint's timeout |

## Token endpoint

`POST /oauth2/token` answers errors in the OAuth2 format, `{"error": "…", "error_description": "…"}`:

| Status | `error` | Meaning |
|---|---|---|
| 400 | `invalid_request` | The request is missing `grant_type` or the client credentials |
| 400 | `unsupported_grant_type` | Only `client_credentials` is supported |
| 401 | `invalid_client` | Unknown client, wrong secret, or a revoked, expired or disabled client |

## Management API

The management API (`/admin/api`) uses the same problem format. Codes you may meet when automating it:

| Status | Code | Meaning |
|---|---|---|
| 400 | `invalid_endpoint`, `invalid_datasource`, `invalid_policy`, … | The request breaks a rule; `detail` says which |
| 409 | `validation_failed` | Publishing found errors in the draft (bad SQL, writes in `GET`, …) |
| 409 | `config_locked` | The workspace is [managed by configuration files](../config/plan-and-apply.md#lock-a-workspace-to-its-files) |
| 409 | `nothing_to_publish` | The saved draft equals the live version |
| 409 | `tests_failed` | Publishing requires passing tests and one failed |
| 409 | `route_taken` / `route_locked` | Another endpoint has this method and path / the path cannot change while live |
| 409 | `plan_changed` | `apply --expected-digest` no longer matches the plan |
| 409 | `datasource_in_use` / `secret_in_use` | Still used by endpoints / datasources or alert channels |
