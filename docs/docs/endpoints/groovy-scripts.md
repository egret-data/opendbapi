---
sidebar_position: 5
---

# Groovy scripts

When a query is easier to build in code than with template tags, write the endpoint as a Groovy script. Choose it in
the **Language** menu of the **Query** tab:

- **Groovy script** (fragment mode): the script builds the query and returns it; OpenDBAPI runs it with the usual
  pagination, row filter, masking, output shaping and cache.
- **Groovy script (full: db, any answer)** (full mode): the script runs its own queries and returns the answer.

Only members with the **script_author**, **admin** or **owner** role can write, change or preview a script. Other
authors can still edit the rest of the endpoint.

## Fragment mode

The script returns a fragment built with `sql"…"`. Every `${…}` inside it becomes a bound parameter, never SQL text:

```groovy
def q = sql"select id, name, tier from customers where 1 = 1"
if (params.tier) q += sql" and tier = ${params.tier}"
if (params.ids) q += sql" and id in (${params.ids})"      // a list becomes ?, ?, ?
q += orderBy([newest: 'id desc', name: 'name'], params.sort, 'id')
return q
```

| Function | What it does |
|---|---|
| `sql"…"` | A fragment; `${value}` is bound, a list becomes one placeholder per item (an empty list is refused) |
| `fragment + fragment` | Joins fragments. Adding a plain string to a fragment is refused |
| `raw("…")` | SQL text written in the script; only a string literal is accepted |
| `orderBy(map, key, fallback)` | ` order by <value>` where the value comes from a map written in the script, chosen by the caller's key; an unknown key uses `fallback` |
| `params` | A read-only map of the bound parameters; validation detects the parameters a script reads as `params.name` or `params['name']` |

The script must return a fragment; returning a plain string is an error.

## Full mode

The script runs queries through `db` and returns any value, which becomes `data` in the response:

```groovy
def customer = db.one(sql"select id, name, tier from customers where id = ${params.id}")
if (!customer) return notFound("No customer ${params.id}")
def orders = db.list(sql"select id, status, total from orders where customer_id = ${customer.id} order by id desc limit 5")
return [customer: customer, recentOrders: orders, spent: db.value(sql"select sum(total) from orders where customer_id = ${customer.id}")]
```

| Function | Returns |
|---|---|
| `db.one(q)` | The first row as a map, or `null` |
| `db.list(q)` | All rows, up to **Most rows returned** |
| `db.value(q)` | The first column of the first row, or `null` |
| `db.update(q)` | The number of rows an `INSERT`, `UPDATE` or `DELETE` changed |
| `notFound(message)` | Ends the request with `404 not_found` |
| `badRequest(message)` | Ends the request with `400 bad_request` |

Every query a script runs goes through the same protection as a saved query, before the script sees its rows:

- the static checks: one statement, `UPDATE` and `DELETE` need a `WHERE`, writes are refused on read-only datasources
  (`403 read_only_datasource`) and in `GET` endpoints (`403 write_in_get`);
- the right function for the statement: `db.update` only for writes, the others only for queries;
- the endpoint's row filter and masks (see [Data policies](../access/data-policies.md));
- the endpoint's timeout and row limit.

A run makes at most 100 queries on one connection, in one transaction when **Transaction** is on (preview and tests
always roll back), and stops after 30 seconds (`opendbapi.script.full-timeout`). Full-mode endpoints answer JSON only:
no pagination or streaming.

## What scripts cannot do

Scripts run in a sandbox, checked when the script is compiled:

- no imports, annotations, class or method declarations, `new`, static calls, `this` or `super`;
- method and property names must be written out, never computed; methods called on values come from an allow list of
  everyday string, number, collection and date methods (Groovy's file and URL helpers are not among them);
- only a short list of types (strings, numbers, booleans, lists, maps, sets, dates, `Math`);
- in full mode, only `one`, `list`, `value` and `update` on `db` itself;
- each run is time-limited: 2 seconds in fragment mode (`opendbapi.script.timeout`).

:::warning
The sandbox lowers the risk but does not prove a script harmless. Give the **script_author** role only to people you trust with the
datasources' credentials, and use a database account that can only read what endpoints need. Set
`opendbapi.script.groovy-enabled=false` to turn scripts off entirely.
:::

A script that fails answers `500 script_failed` to callers, with the request id; the details go to the server log and to
**Preview** for authors.
