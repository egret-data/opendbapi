---
sidebar_position: 3
---

# Cấu hình

Các thiết lập của server, dành cho người vận hành. Đặt chúng dưới dạng Spring property (`application.yml`,
`--name=value`) hoặc biến môi trường: viết hoa, đổi `.` và `-` thành `_`, ví dụ `opendbapi.serving.slow-query-ms` →
`OPENDBAPI_SERVING_SLOW_QUERY_MS`. Giá trị dạng danh sách phân tách bằng dấu phẩy.

## Database và khóa {#database-and-keys}

| Biến môi trường | Mặc định | Ý nghĩa |
|---|---|---|
| `OPENDBAPI_DB_URL` | `jdbc:postgresql://localhost:55432/opendbapi` | PostgreSQL riêng của OpenDBAPI |
| `OPENDBAPI_DB_USER` / `OPENDBAPI_DB_PASSWORD` | `opendbapi_app` / — | Role của ứng dụng, chịu row-level security |
| `OPENDBAPI_DB_OWNER` / `OPENDBAPI_DB_OWNER_PASSWORD` | `opendbapi_owner` / — | Role chủ sở hữu, chạy migration |
| `OPENDBAPI_MASTER_KEY` | — | Base64 của 32 byte; dùng để mã hóa các secret đã lưu |
| `OPENDBAPI_BOOTSTRAP_ADMIN_PASSWORD` | ngẫu nhiên, in ra một lần | Mật khẩu của system admin đầu tiên |
| `OPENDBAPI_SECURE_COOKIES` | `true` trong image | Cookie phiên chỉ gửi qua HTTPS |
| `OPENDBAPI_TRACE_SAMPLING` | `0.1` | Tỷ lệ request được trace |

Server lắng nghe trên cổng 8520. Phiên đăng nhập trên trình duyệt kéo dài 8 giờ.

## `opendbapi.*` {#opendbapi}

| Property | Mặc định | Ý nghĩa |
|---|---|---|
| `auth.local-enabled` | `true` | Cho phép tài khoản cục bộ trên trang đăng nhập; system admin luôn đăng nhập cục bộ được |
| `auth.lockout-attempts` | `5` | Số lần nhập sai mật khẩu cục bộ liên tiếp trước khi tài khoản bị khóa |
| `auth.lockout-duration` | `15m` | Thời gian tài khoản bị khóa |
| `auth.post-login-redirect` | `/` | Trang trình duyệt chuyển tới sau khi đăng nhập SSO |
| `serving.context` | `api` | Đoạn đầu tiên trong đường dẫn của mọi endpoint: `/{context}/{workspace}/…` |
| `serving.url-base` | suy ra từ request | URL gốc công khai, hiển thị trong URL endpoint và tài liệu API |
| `serving.trusted-proxies` | — | IP hoặc dải CIDR của các reverse proxy có header `X-Forwarded-For` được tin cậy |
| `serving.slow-query-ms` | `1000` | Câu lệnh chạy lâu hơn mức này sẽ được ghi nhật ký (chỉ SQL, không có giá trị) |
| `serving.docs-ui` | `true` | Phục vụ `/api/{workspace}/docs.html` |
| `datasources.extra-denied-hosts` | — | Tên host, wildcard `*.suffix` hoặc dải CIDR mà datasource không được truy cập, ngoài các địa chỉ metadata của cloud |
| `datasources.allowed-hosts` | — | Khi được đặt, đây là các host duy nhất datasource được truy cập |
| `access-log.retention-days` | `30` | Số ngày **Hoạt động** giữ lại các lời gọi |
| `alerts.allow-http` | `false` | Cho phép URL webhook dạng `http://` |
| `alerts.dedup-window` | `PT5M` | Mỗi endpoint và mã lỗi chỉ có một cảnh báo trong khoảng thời gian này |
| `cache.max-bytes` | `134217728` (128 MB) | Bộ nhớ dành cho kết quả cache, trên mỗi instance |
| `publish.require-tests-pass` | `false` | Từ chối publish khi test của endpoint thất bại |
| `rate-limit.shared` | `false` (`true` trong Helm chart) | Đếm rate limit chung giữa các instance qua PostgreSQL |
| `script.groovy-enabled` | `true` | Cho phép endpoint và node Groovy script |
| `script.timeout` | `2s` | Thời gian chạy của script ở chế độ fragment |
| `script.full-timeout` | `30s` | Thời gian chạy của script ở chế độ full |
| `tokens.ttl` | `PT15M` | Thời gian sống của access token cấp cho client |
| `ui.read-only` | `false` | Khóa mọi workspace theo file cấu hình |
| `vault.*` | — | Xem [HashiCorp Vault](../deploy/vault.md) |

## Actuator {#actuator}

Chỉ `/actuator/health` (cùng `/liveness` và `/readiness`) và `/actuator/prometheus` được mở ra ngoài.
