---
sidebar_position: 2
---

# Triển khai trên Kubernetes

Helm chart trong `deploy/helm/opendbapi` chạy OpenDBAPI thành các pod giống hệt nhau dùng chung một PostgreSQL. Chart cần
Kubernetes 1.30 trở lên.

## Cài đặt {#install}

Chuẩn bị PostgreSQL như trong [Triển khai bằng Docker](./docker.md#prepare-postgresql), sau đó tạo một Secret và cài chart:

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

## Values {#values}

| Value | Mặc định | Ý nghĩa |
|---|---|---|
| `replicaCount` | `2` | Số pod |
| `image.tag` | `appVersion` của chart | Phiên bản image |
| `database.url` | — (bắt buộc) | JDBC URL của database riêng của OpenDBAPI |
| `database.user` / `database.ownerUser` | `opendbapi_app` / `opendbapi_owner` | Hai role |
| `existingSecret` | — | Secret chứa `db-password`, `db-owner-password`, `master-key` và `bootstrap-admin-password` (tên key nằm trong `secretKeys`) |
| `secrets.*` | — | Hoặc để chart tạo Secret từ các giá trị này (đủ dùng khi thử nghiệm) |
| `rateLimit.shared` | `true` | Đếm rate limit một lần trên tất cả pod |
| `secureCookies` | `true` | Cookie phiên cần HTTPS |
| `vault.*` | tắt | [HashiCorp Vault](./vault.md) |
| `env` / `envFrom` | — | Biến môi trường bổ sung, ví dụ bất kỳ [cấu hình](../reference/configuration.md) nào |
| `ingress.*` | tắt | Ingress với host và TLS |
| `autoscaling.*` | tắt | Horizontal pod autoscaler (2–10 pod ở mức 70 % CPU) |
| `podDisruptionBudget` | `minAvailable: 1` | Giữ lại một pod khi drain node |
| `resources` | 250m CPU, 768Mi–1536Mi bộ nhớ | Tài nguyên của pod |
| `metrics.podAnnotations` | `true` | Annotation `prometheus.io/*` để scrape |

Các pod chạy bằng user không phải root, với root filesystem chỉ đọc và bỏ mọi capability.

## Ingress {#ingress}

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

Đặt `trusted-proxies` bằng mạng pod của ingress controller, để firewall và rate limit theo địa chỉ thấy đúng địa chỉ của
người gọi.

## Mở rộng {#scaling}

Mọi pod đều giống nhau. Phiên trình duyệt, nonce của request có chữ ký, rate limit (khi bật `rateLimit.shared`) và việc
vô hiệu hoá cache đều đi qua PostgreSQL (`LISTEN/NOTIFY`). Vì vậy bạn mở rộng bằng cách thêm pod, không cần sticky
session hay Redis. Câu trả lời đã cache được giữ riêng trên từng pod.
