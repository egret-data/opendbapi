---
sidebar_position: 1
---

# The YAML format

A workspace exports as a stream of YAML documents, one per object, that refer to each other by name instead of by
internal id. That lets you apply the same file to another workspace or another installation.

Export a workspace with **Settings → Import & export → Download YAML**, or with
[`opendbapi export`](./cli-and-ci.md). Passwords, client secrets, signing keys and webhook URLs are never included.

## Documents

Every document has `apiVersion: opendbapi/v1`, a `kind`, and `metadata` and/or `spec`:

| Kind | Identified by | Holds |
|---|---|---|
| `Datasource` | `metadata.name` | Connection settings, pool, `readOnly`, and `passwordRef` (`secret:NAME`, `env:NAME` or `vault:PATH#KEY`) |
| `Group` | `metadata.name` | A folder of endpoints |
| `ServiceAccount` | `metadata.name` | Description, `disabled`, attributes. Clients are not exported: create them per environment |
| `Endpoint` | `spec.method` + `spec.path` | The draft: parameters, query, settings and tests; `metadata.state` (`draft`, `live` or `disabled`) |
| `Grant` | its service account + endpoint or group | Lets a service account call an endpoint (`endpoint: GET /v1/orders`) or a group |
| `DataPolicy` | its column + datasource or endpoint | A mask or row filter |

## Example

An excerpt of the demo workspace:

```yaml
apiVersion: opendbapi/v1
kind: Datasource
metadata:
  name: shop
spec:
  dialect: POSTGRESQL
  host: postgres
  port: 5432
  database: opendbapi
  username: shop_reader
  properties:
    currentSchema: shop
  readOnly: true
  passwordRef: secret:shop-reader-password
---
apiVersion: opendbapi/v1
kind: Group
metadata:
  name: Customers
---
apiVersion: opendbapi/v1
kind: ServiceAccount
metadata:
  name: reporting
spec:
  description: Nightly reporting job
  attributes:
    country: VN
---
apiVersion: opendbapi/v1
kind: Endpoint
metadata:
  name: Customer
  group: Customers
  state: live
spec:
  method: GET
  path: "v1/customers/{id}"
  access: private
  datasource: shop
  params:
  - name: id
    in: path
    type: bigint
    required: true
  task:
    type: sql
    statements:
    - id: main
      template: "select id, name, email, country, tier from customers where id = #{id}"
  output:
    naming: camelCase
    shape: single
---
apiVersion: opendbapi/v1
kind: Grant
spec:
  serviceAccount: reporting
  group: Customers
---
apiVersion: opendbapi/v1
kind: DataPolicy
spec:
  kind: mask
  column: customers.email
  datasource: shop
  mask:
    type: email
```

Settings left out take their defaults, so a hand-written file can be much shorter than an export.

An [HTTP API](../datasources/http-apis.md) datasource has `type: http` and its settings under `spec`; its endpoints have
an `http` task:

```yaml
apiVersion: opendbapi/v1
kind: Datasource
metadata:
  name: orders-api
spec:
  type: http
  baseUrl: https://api.example.com/v1
  auth: bearer
  headers:
    X-Tenant: acme
  readOnly: true
  passwordRef: secret:orders-api-token
---
apiVersion: opendbapi/v1
kind: Endpoint
metadata:
  name: Customer orders
spec:
  method: GET
  path: "v1/customers/{id}/orders"
  access: private
  datasource: orders-api
  params:
  - name: id
    in: path
    type: bigint
    required: true
  task:
    type: http
    request:
      method: GET
      path: "/customers/{id}/orders"
      query:
      - name: status
        value: "'open'"
      resultPath: /data
```

## Splitting files

Keep one file per area or per endpoint if you like: [`plan` and `apply`](./plan-and-apply.md) read several files, or every
`*.yaml` and `*.yml` in a directory, as one stream. Passwords never belong in the files; a `password` field is refused.
