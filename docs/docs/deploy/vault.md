---
sidebar_position: 4
---

# HashiCorp Vault

Datasource passwords, webhook URLs and identity provider secrets can be read from HashiCorp Vault instead of being stored
in OpenDBAPI. Write a reference in place of the value:

```text
vault:secret/opendbapi/sales/db#password
```

With KV version 2 (the default) this reads `GET /v1/secret/data/opendbapi/sales/db` and returns its `password` field.

## Connect OpenDBAPI to Vault

| Setting | Default | Meaning |
|---|---|---|
| `opendbapi.vault.address` | — | e.g. `https://vault.example.com:8200`; empty disables `vault:` references |
| `opendbapi.vault.auth` | `token` | `token`, `approle` or `kubernetes` |
| `opendbapi.vault.token` | — | For `token` auth |
| `opendbapi.vault.role-id` / `secret-id` | — | For `approle` auth |
| `opendbapi.vault.role` | — | For `kubernetes` auth: the Vault role trusting the pod's service account |
| `opendbapi.vault.jwt-path` | the pod's service account token | For `kubernetes` auth |
| `opendbapi.vault.login-mount` | `approle` / `kubernetes` | Auth mount path |
| `opendbapi.vault.namespace` | — | Vault Enterprise namespace |
| `opendbapi.vault.kv-version` | `2` | `1` or `2` |
| `opendbapi.vault.allowed-paths` | `secret/opendbapi/{workspace}/` | Paths a workspace may read; `{workspace}` is its slug. Comma-separated |
| `opendbapi.vault.system-paths` | `secret/opendbapi/system/` | Paths server-level references (identity providers) may read |
| `opendbapi.vault.cache-seconds` | `60` | How long a value is cached |

On Kubernetes, set the chart's `vault.*` values instead; with `auth: kubernetes` Vault's Kubernetes auth role must trust
the release's service account.

## Why paths are restricted

A workspace admin chooses the reference. Without a restriction, one workspace's admin could point a datasource at a host
of their choosing with another workspace's secret as its password. So each workspace may only read paths under
`allowed-paths` with its own slug, and a reference outside them is refused.

The Vault token from AppRole or Kubernetes login is kept until shortly before its lease ends, then OpenDBAPI logs in again.
