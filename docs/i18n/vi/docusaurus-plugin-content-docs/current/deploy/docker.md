---
sidebar_position: 1
---

# Triển khai bằng Docker

OpenDBAPI là một image duy nhất, `ghcr.io/egret-data/opendbapi`, phục vụ giao diện quản trị, API quản trị và các endpoint
đã publish trên cổng 8520. Mọi dữ liệu được lưu trong một cơ sở dữ liệu PostgreSQL.

## Chuẩn bị PostgreSQL {#prepare-postgresql}

OpenDBAPI cần PostgreSQL 14 trở lên cho dữ liệu riêng của nó (người dùng, workspace, endpoint, hoạt động). Cơ sở dữ liệu
này tách biệt với các cơ sở dữ liệu mà endpoint của bạn query. Tạo database và hai role: role **owner** chạy migration
schema, và role **app** mà server dùng khi chạy, chịu ràng buộc row-level security.

```sql
CREATE ROLE opendbapi_owner LOGIN PASSWORD 'change-me-owner';
CREATE ROLE opendbapi_app   LOGIN PASSWORD 'change-me-app' NOBYPASSRLS;
CREATE DATABASE opendbapi OWNER opendbapi_owner;
```

Khi khởi động, OpenDBAPI tạo và nâng cấp schema của nó (`opendbapi`) bằng role owner.

## Tạo master key {#generate-a-master-key}

Master key mã hoá các secret được lưu: mật khẩu datasource, khoá ký, secret của nhà cung cấp định danh.

```bash
openssl rand -base64 32
```

:::warning
Giữ master key an toàn và cố định. Secret đã lưu bằng một key không đọc được bằng key khác, và ai có key cùng một bản
backup database đều đọc được chúng.
:::

## Chạy {#run}

```bash
docker run -d --name opendbapi -p 8520:8520 \
  -e OPENDBAPI_DB_URL=jdbc:postgresql://db.example.com:5432/opendbapi \
  -e OPENDBAPI_DB_PASSWORD=change-me-app \
  -e OPENDBAPI_DB_OWNER_PASSWORD=change-me-owner \
  -e OPENDBAPI_MASTER_KEY=... \
  -e OPENDBAPI_BOOTSTRAP_ADMIN_PASSWORD=... \
  ghcr.io/egret-data/opendbapi:0.3.0
```

| Biến | Ý nghĩa |
|---|---|
| `OPENDBAPI_DB_URL` | JDBC URL của database riêng của OpenDBAPI |
| `OPENDBAPI_DB_USER` / `OPENDBAPI_DB_PASSWORD` | Role app (user mặc định `opendbapi_app`) |
| `OPENDBAPI_DB_OWNER` / `OPENDBAPI_DB_OWNER_PASSWORD` | Role owner (user mặc định `opendbapi_owner`) |
| `OPENDBAPI_MASTER_KEY` | Base64 của 32 byte ngẫu nhiên |
| `OPENDBAPI_BOOTSTRAP_ADMIN_PASSWORD` | Mật khẩu của user `admin` được tạo ở lần khởi động đầu tiên. Nếu không đặt, một mật khẩu ngẫu nhiên được in một lần trong nhật ký |
| `OPENDBAPI_SECURE_COOKIES` | `true` trong image: cookie phiên cần HTTPS. Chỉ đặt `false` khi thử nghiệm qua HTTP thường |

Mọi cấu hình khác cũng có thể truyền bằng biến môi trường; xem [Cấu hình](../reference/configuration.md).

## Đặt sau HTTPS {#put-it-behind-https}

Chạy OpenDBAPI sau một reverse proxy hoặc load balancer kết thúc TLS. Khai báo các proxy được tin cậy để OpenDBAPI đọc
địa chỉ người gọi từ `X-Forwarded-For`, và địa chỉ công khai để hiển thị trong URL của endpoint:

```bash
-e OPENDBAPI_SERVING_TRUSTED_PROXIES=10.0.0.0/8
-e OPENDBAPI_SERVING_URL_BASE=https://opendbapi.example.com
```

Khi `url-base` để trống, OpenDBAPI suy ra địa chỉ từ từng request.

## Health và metrics {#health-and-metrics}

| Đường dẫn | Dùng cho |
|---|---|
| `/actuator/health/liveness` | Liveness probe |
| `/actuator/health/readiness` | Readiness probe (`HEALTHCHECK` của image dùng nó) |
| `/actuator/prometheus` | Metrics Prometheus; xem [Vận hành](./operations.md) |

Image chạy bằng user không phải root `10001` và chạy được với root filesystem chỉ đọc.
