---
sidebar_position: 1
---

# Datasources

A datasource is a database that endpoints query. Workspace admins connect them; authors choose one per endpoint.

![The shop datasource: PostgreSQL, read only, connected](/img/datasources/detail.png)

## Connect a database

1. Open **Datasources** and press **New datasource**.
2. Fill in the settings below, press **Test connection**, and when it says **Connected.** press **Create datasource**.

### Connection

| Setting | What it is |
|---|---|
| **Name** | How endpoints and people refer to it; unique in the workspace |
| **Description** | Optional |
| **Database type** | PostgreSQL, MySQL, MariaDB, SQL Server, Oracle and others; drivers for these are built in |
| **Host** | Host name or IP address, without port |
| **Port** | Leave empty for the type's default |
| **Database** | The database to connect to (for Oracle, the service name, e.g. `ORCLPDB1`) |
| **Driver properties** | Only properties that are safe to set are offered, e.g. `currentSchema` for PostgreSQL |

### Credentials

| Setting | What it is |
|---|---|
| **Username** | Prefer an account that can only read what endpoints need |
| **Password** | Stored encrypted with the server's master key. Or type `secret:NAME` for a [managed secret](../workspace/secrets.md), `env:NAME` to read it from the server's environment, or `vault:PATH#KEY` for [HashiCorp Vault](../deploy/vault.md) |

Leave **Password** empty when editing to keep the stored one.

### Pool

| Setting | Default |
|---|---|
| **Maximum connections** | Per server instance, 1 to 50 |
| **Idle connections kept** | 0 opens connections only when needed |
| **Connection timeout** | How long a request waits for a free connection, in milliseconds |

### Safety

**Read only** refuses `INSERT`, `UPDATE`, `DELETE` and DDL in every endpoint on the datasource, at validation and when
scripts run. The JDBC connection is opened read-only too.

## Tables and usage

- **Tables** browses the datasource's schemas, tables and columns, to help write queries.
- **Usage** lists the endpoints that use it. A datasource still in use cannot be deleted.
- **Masking** holds [data policies](../access/data-policies.md) for every endpoint on the datasource.

![The Tables tab listing the shop schema's tables](/img/datasources/tables.png)

## Which hosts are allowed

Cloud metadata addresses (such as `169.254.169.254`) are always refused. Operators can refuse more hosts with
`opendbapi.datasources.extra-denied-hosts`, or allow only a list with `opendbapi.datasources.allowed-hosts`; see
[Configuration](../reference/configuration.md).

## Changing a datasource

Saving new settings, or changing the [secret](../workspace/secrets.md) its password reads, closes its connection pool on
every server instance; the next query reconnects with the new settings.
