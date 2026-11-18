---
sidebar_position: 2
---

# Secrets

A secret is a named value the workspace keeps encrypted, such as a database password or a webhook URL. Datasources and
alert channels refer to it as `secret:NAME`, so changing the value once updates everything that uses it, and the value
never appears in an export or a configuration file.

![The Secrets tab listing shop-reader-password and where it is used](/img/workspace/secrets.png)

Secrets are for workspace admins.

## Add a secret

1. Open **Settings → Secrets** and press **Add secret**.
2. Enter a **Name** (lower-case letters, digits, `.`, `_` and `-`; it cannot be renamed later), the **Value** and an
   optional **Description (optional)**.
3. Press **Save**.

The value is write-only: nobody can read it back, only replace it with **Change**. It is sealed with AES-256-GCM using
the server's master key.

## Use a secret

Type `secret:NAME` instead of a value:

- in a datasource's **Password** (see [Datasources](../datasources/datasources.md));
- in an alert channel's **Webhook URL** (see [Alerts](./alerts.md)).

**Used by** lists what refers to each secret. A secret still in use cannot be deleted. Changing a secret used by a
datasource closes that datasource's connections on every server instance, so the new password takes effect at once.

## Other places a value can come from

| Reference | Read from |
|---|---|
| `secret:NAME` | This workspace's secrets |
| `env:NAME` | An environment variable (or Spring property) of the OpenDBAPI server |
| `vault:PATH#KEY` | [HashiCorp Vault](../deploy/vault.md), under the paths the server allows for the workspace |
