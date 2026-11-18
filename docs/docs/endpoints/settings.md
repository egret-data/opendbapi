---
sidebar_position: 2
---

# Endpoint settings

The **Settings** tab of an endpoint: how it is listed, how results are paged and shaped, caching, streaming, limits,
parameter rules and alerts. Changes take effect when you publish.

![The Settings tab of the Customers endpoint with the Endpoint and Pagination groups](/img/endpoints/settings.png)

## Endpoint

| Setting | What it does |
|---|---|
| **Name** | How it is listed |
| **Description** | Shown in the generated API documentation |
| **Method** | Fixed while live: callers depend on it. Disable the endpoint to change it |
| **Path** | Fixed while live, for the same reason. `{name}` segments become path parameters |
| **Access** | **Private** needs a client token; **Public** answers anyone |
| **Group** | A folder for the list; not part of the URL |

## Pagination

| Setting | What it does | Default |
|---|---|---|
| **Paginate** | Callers ask for one page at a time; OpenDBAPI adds the paging clause for the database | Off |
| **Default page size** | Rows per page when the caller does not pass `size` | 20 |
| **Largest page size** | The biggest `size` a caller may ask for | 500 |
| **Count total** | Offset mode: adds a `COUNT` query per call and returns `meta.total`; off returns only `meta.hasNext` | On |
| **Mode** | **Offset** uses `page` and `size`. **Keyset** returns a cursor for the next page: steady under inserts and fast on deep pages, but no jumping to page N | Offset |
| **Key columns** | Keyset mode: the result columns the pages are ordered by; never null and together unique, so end with an id. Mark a key **Newest / largest first** to page downwards | — |

With pagination on, `page`, `size`, `after` and `format` are reserved parameter names.

```text
GET /api/demo/v1/customers?page=2&size=20       offset mode
GET /api/demo/v1/events?size=50&after=eyJr…     keyset mode: after = meta.nextCursor of the previous page
```

## Output

| Setting | What it does |
|---|---|
| **Shape** | **List** (an array of rows), **Single object** (the first row; no row answers `404 not_found`), or **Scalar value** (the first column of the first row) |
| **Key names** | **As in SQL**, or **camelCase**, which turns `created_at` into `createdAt` |
| **Value types** | Convert a column's values: **Decimal as text** (money as text, so JavaScript keeps every digit), **ISO 8601 date**, **Epoch milliseconds**, **Boolean (0/1)** |
| **Nesting** | Group flat join rows into objects with collections: choose the **id** column, then a collection per prefix (e.g. `order_` columns into `orders`) |

## Cache

| Setting | What it does | Default |
|---|---|---|
| **Cache answers** | Keep successful `GET` answers in memory and serve them again until they expire; `meta.cached` tells callers | Off |
| **Keep for** | Seconds, up to 86 400 (one day) | 60 |
| **Shared between callers** | One entry for all callers instead of one per service account. Not allowed when data policies make answers differ per caller | Off |

**Clear cache** drops this endpoint's cached answers. Try-it calls are never cached. Cached answers are kept per server
instance, up to 128 MB (`opendbapi.cache.max-bytes`).

## Streaming

| Setting | What it does |
|---|---|
| **Allow streaming** | Callers may ask for `?format=ndjson` or `?format=csv` (or the `Accept` header) to receive rows as they are read, without pagination |
| **Formats** | NDJSON, CSV, or both |
| **Most rows streamed** | Empty is the same as **Most rows returned**; up to 10 000 000 |

## Limits

| Setting | What it does | Default |
|---|---|---|
| **Timeout** | Seconds; the query is cancelled after this and the caller gets `504 query_timeout` | 30 |
| **Most rows returned** | More rows are cut off and `meta.truncated` is set | 10 000 |
| **Rate limit** | Calls per minute this endpoint answers across all callers; more answer `429 rate_limited` with `Retry-After` | None |
| **Transaction** | Run all statements in one transaction; any failure rolls them all back | Off |

Workspace-wide limits per client and per IP address are in [Security](../workspace/security.md).

## Parameter rules

Checks across parameters that run before the query, written in the same expression language as `<if test>`, each with a
**Message for callers**. A rule that does not hold (or cannot be evaluated) answers `400 rule_failed` with the message. At
most 20 rules.

```text
from == null or to == null or from <= to       "from must be before to"
ids == null or ids.size() <= 100               "Pass at most 100 ids"
```

## Alerts

**Alert on failures** sends server errors (5xx) to the workspace's [alert channels](../workspace/alerts.md), at most once per
error every few minutes.

## Further reading

- [Write and publish endpoints](./write-and-publish.md)
- [Calling endpoints](../access/calling-endpoints.md): the response envelope, pages and streaming from the caller's side.
