---
sidebar_position: 4
---

# HashiCorp Vault

Mật khẩu datasource, URL webhook và secret của nhà cung cấp định danh có thể được đọc từ HashiCorp Vault thay vì lưu trong
OpenDBAPI. Hãy viết một tham chiếu vào chỗ của giá trị:

```text
vault:secret/opendbapi/sales/db#password
```

Với KV phiên bản 2 (mặc định), tham chiếu này đọc `GET /v1/secret/data/opendbapi/sales/db` và trả về trường `password`.

## Kết nối OpenDBAPI với Vault {#connect-opendbapi-to-vault}

| Cấu hình | Mặc định | Ý nghĩa |
|---|---|---|
| `opendbapi.vault.address` | — | Ví dụ `https://vault.example.com:8200`; để trống sẽ tắt tham chiếu `vault:` |
| `opendbapi.vault.auth` | `token` | `token`, `approle` hoặc `kubernetes` |
| `opendbapi.vault.token` | — | Cho kiểu xác thực `token` |
| `opendbapi.vault.role-id` / `secret-id` | — | Cho kiểu xác thực `approle` |
| `opendbapi.vault.role` | — | Cho kiểu xác thực `kubernetes`: role của Vault tin cậy service account của pod |
| `opendbapi.vault.jwt-path` | token service account của pod | Cho kiểu xác thực `kubernetes` |
| `opendbapi.vault.login-mount` | `approle` / `kubernetes` | Đường dẫn mount của auth |
| `opendbapi.vault.namespace` | — | Namespace của Vault Enterprise |
| `opendbapi.vault.kv-version` | `2` | `1` hoặc `2` |
| `opendbapi.vault.allowed-paths` | `secret/opendbapi/{workspace}/` | Các đường dẫn một workspace được đọc; `{workspace}` là slug của nó. Phân tách bằng dấu phẩy |
| `opendbapi.vault.system-paths` | `secret/opendbapi/system/` | Các đường dẫn mà tham chiếu cấp server (nhà cung cấp định danh) được đọc |
| `opendbapi.vault.cache-seconds` | `60` | Thời gian cache một giá trị |

Trên Kubernetes, hãy đặt các value `vault.*` của chart thay thế. Với `auth: kubernetes`, role Kubernetes auth của Vault
phải tin cậy service account của release.

## Vì sao đường dẫn bị giới hạn {#why-paths-are-restricted}

Admin của workspace là người chọn tham chiếu. Nếu không giới hạn, admin của một workspace có thể trỏ datasource tới một
host do họ chọn và dùng secret của workspace khác làm mật khẩu. Vì vậy mỗi workspace chỉ được đọc các đường dẫn dưới
`allowed-paths` có slug của chính nó, và tham chiếu nằm ngoài các đường dẫn đó sẽ bị từ chối.

Token Vault có được từ đăng nhập AppRole hoặc Kubernetes được giữ cho tới ngay trước khi lease hết hạn, sau đó OpenDBAPI
đăng nhập lại.
