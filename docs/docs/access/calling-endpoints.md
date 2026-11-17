---
sidebar_position: 2
---

# Calling endpoints

What a program sends to call a published endpoint, and what it gets back.

## URL

```text
{base}/api/{workspace}/{path}
```

For example `https://opendbapi.example.com/api/demo/v1/customers/3`. The `api` prefix is `opendbapi.serving.context`.
Each workspace publishes an OpenAPI document of its live endpoints at `/api/{workspace}/openapi.json`, browsable at
`/api/{workspace}/docs.html`.

## Get a token

Private endpoints need a bearer token. Exchange a client's id and secret for one with the OAuth2 client-credentials
grant, authenticating with HTTP Basic (or `client_id` and `client_secret` form fields):

```bash
curl -s -u "$CLIENT_ID:$CLIENT_SECRET" -d grant_type=client_credentials https://opendbapi.example.com/oauth2/token
```

```json
{"access_token": "eyJraWQiOiJi…", "token_type": "Bearer", "expires_in": 900}
```

Tokens last 15 minutes (`opendbapi.tokens.ttl`); get a new one when it expires. A wrong id or secret answers
`401 invalid_client`.

## Call

```bash
curl -s -H "Authorization: Bearer $TOKEN" "https://opendbapi.example.com/api/demo/v1/customers?tier=gold"
```

Public endpoints need no `Authorization` header.

### Parameters

Pass parameters where the endpoint declares them: in the path, the query string, a header, or a JSON body
(`Content-Type: application/json`, at most 1 MB). Arrays in the query string repeat the name or separate values with commas: `?ids=1&ids=2` and `?ids=1,2` are the same.

## The response

```json
{
  "data": [{"id": 1, "name": "Ada Lovelace", "tier": "gold"}],
  "meta": {
    "version": 3,
    "cached": false,
    "page": 1, "size": 20, "total": 2, "hasNext": false,
    "requestId": "cfbf7475-0d34-4002-bfc0-a20e6dbdd64f"
  }
}
```

| Field | Meaning |
|---|---|
| `data` | A list, an object or a value, depending on the endpoint's output shape. A write answers `{"affectedRows": n}` |
| `meta.version` | The published version that answered (also the `X-OpenDBAPI-Version` header) |
| `meta.cached` | Whether the cache answered |
| `meta.page`, `size`, `total`, `hasNext` | Offset pagination (`total` only when the endpoint counts) |
| `meta.nextCursor` | Keyset pagination: pass it as `after` for the next page |
| `meta.truncated` | `true` when more rows existed than the endpoint returns |
| `meta.requestId` | Quote it when reporting a problem; it is in **Activity** too (also the `X-Request-Id` header) |

A deprecated endpoint adds `Deprecation`, `Sunset` and `Link` headers; see
[Major versions and deprecation](../endpoints/versions-and-majors.md).

### Pages

```text
?page=2&size=50          offset pagination
?size=50&after=<cursor>  keyset pagination
```

### Streaming

When the endpoint allows it, ask for `?format=ndjson` (one JSON object per line) or `?format=csv`, or send the matching
`Accept` header. Streams are not paginated; a stream cut short ends with a `{"_meta": …}` line.

## Errors

Errors are RFC 9457 problems with a stable `code`:

```json
{
  "type": "about:blank",
  "title": "Unauthorized",
  "status": 401,
  "detail": "This endpoint needs an access token: Authorization: Bearer <token>.",
  "code": "unauthenticated",
  "requestId": "dccc29a5-b40d-4a88-b1fc-f6ad41263471"
}
```

The [error reference](../reference/errors.md) lists every code. `429` answers carry `Retry-After`, and `401` answers carry
`WWW-Authenticate`.
