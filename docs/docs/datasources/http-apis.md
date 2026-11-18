---
sidebar_position: 2
---

# HTTP APIs

An HTTP datasource is another REST API that endpoints call instead of a database. An endpoint on it sends one request,
built from its parameters, and answers the JSON that comes back as rows. Use it to put an existing API behind
OpenDBAPI's access control, rate limits, masking, caching and activity log, or to combine it with your databases in a
[workflow](../endpoints/workflows.md).

## Connect an API

1. Open **Datasources** and press **New datasource**.
2. Set **Type** to **HTTP API** and fill in the settings below.
3. Press **Test connection**: it sends `GET` to the **Base URL** and reports the status. A 401 or 403 means the API
   refused the credentials; a 5xx that it is failing. Other answers, a 404 included, mean it can be reached.
4. Press **Create datasource**.

### Connection

| Setting | What it is |
|---|---|
| **Base URL** | `http://` or `https://`, a host, and an optional path, e.g. `https://api.example.com/v1`. No user name, query or fragment |
| **Headers** | Sent with every request, e.g. `X-Tenant: acme`. They are stored as plain text, so `Authorization`, `Cookie` and other credentials are refused here |
| **Connection timeout** | How long to wait for a connection, in milliseconds (250 to 60000). The endpoint's **Timeout** setting limits the whole request |

### Authentication

| **Authentication** | Sends | Secret field |
|---|---|---|
| **None** | Nothing | — |
| **Bearer token** | `Authorization: Bearer <token>` | **Token** |
| **Basic (username and password)** | `Authorization: Basic …` with the **Username** | **Password** |
| **API key in a header** | The key in **Header name**, e.g. `X-API-Key` | **API key** |

The secret is stored like a database password: encrypted, or as a `secret:NAME`, `env:NAME` or `vault:PATH#KEY`
reference. It is sent to the base URL's host only.

### Safety

- **Read only**: endpoints may send `GET` requests only; others are refused at validation and when called.
- Redirects are not followed: a `3xx` answer is an error, so a request cannot be sent on to another host.
- The host is checked on every request against the hosts datasources may reach (see
  [Datasources](./datasources.md#which-hosts-are-allowed)).
- At most 10 MB of response is read.

## Write the request

When an endpoint's datasource is an HTTP API, its **Query** tab shows the request instead of SQL. Choosing the
datasource switches the **Language** to **HTTP request**.

| Field | What it is |
|---|---|
| **Request** | The method (`GET`, `POST`, `PUT`, `PATCH` or `DELETE`) and the path after the base URL, e.g. `/orders/{id}` |
| **Query** | Query string fields: a name and a value |
| **JSON body** | Fields of a JSON object sent as the body; not for `GET`. Leave it empty to send no body |
| **Rows at** | A [JSON Pointer](https://datatracker.ietf.org/doc/html/rfc6901) to the rows in the response, e.g. `/data/items`; empty for the whole response |

Values, and `{…}` in the path, are expressions over the endpoint's parameters, as in `<if test>`: a parameter name such as
`status`, a field such as `customer.id`, or text in quotes such as `'open'`.

- Path values are encoded, so a value cannot add `/`, `?` or `&` to the URL.
- A query value that is a list is sent as the field repeated (`tag=a&tag=b`). A value that is null is left out.
- **Detect from request**, above the parameters, adds the names the request uses.

For example, with the base URL `https://api.example.com/v1`, the request `GET /customers/{id}/orders`, the query
field `status` ← `status` and **Rows at** `/data`, a call with `id=7&status=open` sends:

```text
GET https://api.example.com/v1/customers/7/orders?status=open
```

and answers the array at `data` in the response.

### Rows from the response

| At **Rows at** | Rows |
|---|---|
| An array | One row per item. An object's fields are the columns; anything else becomes a `value` column |
| An object | One row |
| `null`, or an empty body | No rows |
| Nothing | Error `upstream_failed` |

The rows then go through the endpoint's settings like a query result: masks, [output](../endpoints/settings.md#output)
mapping (names, shape, types), **Most rows returned** and the cache.

## How calls end

| The API answers | The caller gets |
|---|---|
| 2xx with JSON | 200 with the rows |
| 404 | 404 `not_found` |
| Any other status, a redirect, or a body that is not JSON | 502 `upstream_failed` |
| Nothing within the endpoint's timeout | 504 `query_timeout` |

## Limits

- **Preview** sends `GET` requests only: an API cannot roll a `POST`, `PUT`, `PATCH` or `DELETE` back. Publish the endpoint
  and call it, or use **Try it**, to send one.
- No pagination, streaming or transactions: pass page parameters on to the API under **Query** instead.
- Column masks apply to the rows; row filters need SQL, so an endpoint under a row filter is refused.
- The **Tables** tab is not shown for HTTP APIs.
