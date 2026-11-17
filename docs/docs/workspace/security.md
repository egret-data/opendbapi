---
sidebar_position: 4
---

# Security

Workspace-wide limits for everyone who calls the workspace's endpoints: a firewall by caller address and rate limits per
client and per address. They apply to every endpoint of the workspace, public or private.

![The Security tab with the firewall and rate limits](/img/workspace/security.png)

Open **Settings → Security**. Workspace admins can change it.

## Firewall

| Setting | What it does |
|---|---|
| **Firewall mode** | **Off**, **Allow list** (only listed addresses may call) or **Deny list** (listed addresses may not) |
| **Addresses** | One IP address or CIDR range per line, e.g. `10.0.0.0/8` or `2001:db8::/32` |

Refused calls answer `403 ip_blocked`. Behind a load balancer or ingress, set `opendbapi.serving.trusted-proxies` so
OpenDBAPI reads the caller's address from `X-Forwarded-For`; otherwise every call seems to come from the proxy.

## Rate limits

| Setting | What it does |
|---|---|
| **Per client** | Calls per minute for each OAuth2 client calling private endpoints. Empty = no limit |
| **Per IP address** | Calls per minute for each caller address on public endpoints. Empty = no limit |

More calls answer `429 rate_limited` with a `Retry-After` header. Endpoints can have their own limit too (see
[Endpoint settings](../endpoints/settings.md#limits)).

Every instance applies a change within a few seconds. Limits are counted per instance unless
`opendbapi.rate-limit.shared=true`, which counts them in PostgreSQL across all instances; the Helm chart turns it on.
