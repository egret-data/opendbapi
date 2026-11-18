---
sidebar_position: 2
---

# Deploy on Kubernetes

The Helm chart in `deploy/helm/opendbapi` runs OpenDBAPI as identical pods on one PostgreSQL. It needs Kubernetes 1.30 or
later.

## Install

Prepare PostgreSQL as in [Deploy with Docker](./docker.md#prepare-postgresql), then create a Secret and install the chart:

```bash
kubectl create secret generic opendbapi \
  --from-literal=db-password=... \
  --from-literal=db-owner-password=... \
  --from-literal=master-key="$(openssl rand -base64 32)" \
  --from-literal=bootstrap-admin-password=...

helm install opendbapi deploy/helm/opendbapi \
  --set database.url=jdbc:postgresql://postgres:5432/opendbapi \
  --set existingSecret=opendbapi \
  --set replicaCount=3
```

## Values

| Value | Default | Meaning |
|---|---|---|
| `replicaCount` | `2` | Number of pods |
| `image.tag` | the chart's `appVersion` | Image version |
| `database.url` | — (required) | JDBC URL of OpenDBAPI's own database |
| `database.user` / `database.ownerUser` | `opendbapi_app` / `opendbapi_owner` | The two roles |
| `existingSecret` | — | Secret with `db-password`, `db-owner-password`, `master-key` and `bootstrap-admin-password` (key names in `secretKeys`) |
| `secrets.*` | — | Or let the chart create the Secret from these values (fine for a trial) |
| `rateLimit.shared` | `true` | Count rate limits once across all pods |
| `secureCookies` | `true` | Session cookies need HTTPS |
| `vault.*` | disabled | [HashiCorp Vault](./vault.md) |
| `env` / `envFrom` | — | Extra environment, e.g. any [setting](../reference/configuration.md) |
| `ingress.*` | disabled | Ingress with hosts and TLS |
| `autoscaling.*` | disabled | Horizontal pod autoscaler (2–10 pods at 70 % CPU) |
| `podDisruptionBudget` | `minAvailable: 1` | Keeps a pod during node drains |
| `resources` | 250m CPU, 768Mi–1536Mi memory | Pod resources |
| `metrics.podAnnotations` | `true` | `prometheus.io/*` annotations for scraping |

The pods run as non-root with a read-only root filesystem and all capabilities dropped.

## Ingress

```yaml
ingress:
  enabled: true
  className: nginx
  hosts:
    - host: opendbapi.example.com
      paths:
        - path: /
          pathType: Prefix
  tls:
    - secretName: opendbapi-tls
      hosts: [opendbapi.example.com]
env:
  - name: OPENDBAPI_SERVING_TRUSTED_PROXIES
    value: 10.0.0.0/8
  - name: OPENDBAPI_SERVING_URL_BASE
    value: https://opendbapi.example.com
```

Set `trusted-proxies` to the ingress controller's pod network, so the firewall and per-address rate limits see real
caller addresses.

## Scaling

Every pod is identical. Browser sessions, signed-request nonces, rate limits (with `rateLimit.shared`) and cache
invalidation go through PostgreSQL (`LISTEN/NOTIFY`), so scale by adding pods: no sticky sessions and no Redis. Cached
answers are kept per pod.
