---
sidebar_position: 3
---

# Đăng nhập: cục bộ, LDAP và SSO

Người dùng đăng nhập vào giao diện quản trị bằng **tài khoản cục bộ**, qua thư mục **LDAP / Active Directory**, hoặc qua
**SSO (OpenID Connect)** như Keycloak, Entra ID, Okta hay Google. Chương trình gọi endpoint thì dùng
[client](../access/clients.md), không dùng các tài khoản này.

## Tài khoản cục bộ {#local-accounts}

Ở lần khởi động đầu tiên, OpenDBAPI tạo quản trị viên hệ thống `admin` với mật khẩu `OPENDBAPI_BOOTSTRAP_ADMIN_PASSWORD`
(hoặc một mật khẩu ngẫu nhiên được in một lần trong nhật ký). Quản trị viên hệ thống tạo thêm người dùng cục bộ tại
**Quản trị → Người dùng → Người dùng cục bộ mới**, và cũng đặt lại mật khẩu hay vô hiệu hoá người dùng ở đó.

Sau 5 lần nhập sai mật khẩu liên tiếp, tài khoản bị khoá 15 phút (`opendbapi.auth.lockout-attempts`,
`opendbapi.auth.lockout-duration`); đặt lại mật khẩu sẽ gỡ khoá. Khi SSO đã chạy, ẩn đăng nhập cục bộ bằng
`opendbapi.auth.local-enabled=false`. Quản trị viên hệ thống vẫn đăng nhập cục bộ được, để còn đường vào khi nhà cung cấp
định danh gặp sự cố.

Người dùng tự đổi mật khẩu từ menu người dùng → **Đổi mật khẩu**.

## Thêm nhà cung cấp định danh {#add-an-identity-provider}

1. Trong **Quản trị → Nhà cung cấp định danh**, bấm **Nhà cung cấp mới**.
2. Chọn **Loại nhà cung cấp**, đặt **Tên** (hiển thị trên trang đăng nhập), điền các cấu hình bên dưới và bấm
   **Tạo nhà cung cấp**.
3. Dùng **Kiểm tra kết nối** (với LDAP có thể thử thêm **Tên đăng nhập để thử (không bắt buộc)**).
4. Ánh xạ nhóm sang vai trò ở tab **Ánh xạ nhóm**.

### LDAP / Active Directory {#ldap--active-directory}

| Cấu hình | Ví dụ |
|---|---|
| **URL máy chủ** | `ldaps://ldap.example.com:636` |
| **StartTLS** / **Cho phép không mã hoá** | Dùng StartTLS với `ldap://`; chỉ cho phép không mã hoá khi thử nghiệm |
| **DN của service account** và **Mật khẩu** | `cn=opendbapi,ou=services,dc=example,dc=com` |
| **Gốc tìm người dùng** / **Bộ lọc tìm người dùng** | `ou=users,dc=example,dc=com` / `(uid={0})` (Active Directory: `(sAMAccountName={0})`) |
| **Gốc tìm nhóm** / **Bộ lọc tìm nhóm** | `ou=groups,dc=example,dc=com` / `(member={0})` |
| **Thuộc tính email**, **Thuộc tính tên hiển thị** | `mail`, `cn` |

### SSO (OpenID Connect) {#sso-openid-connect}

| Cấu hình | Ví dụ |
|---|---|
| **URL nhà phát hành** | `https://keycloak.example.com/realms/acme` |
| **Client ID** và secret | Lấy từ nhà cung cấp định danh |
| **Scope** | `openid profile email` |
| **Claim tên đăng nhập** | `preferred_username` |
| **Claim nhóm** | `groups` |

Đăng ký redirect URI mà trang hiển thị tại nhà cung cấp định danh:

```text
https://opendbapi.example.com/login/oauth2/code/{provider id}
```

Secret có thể nhập dạng `env:NAME` để đọc từ biến môi trường của server, hoặc `vault:PATH#KEY` dưới các đường dẫn hệ
thống của Vault; nếu không, secret được lưu ở dạng mã hoá.

## Ánh xạ nhóm sang vai trò {#map-groups-to-roles}

Trong **Ánh xạ nhóm**, bấm **Thêm ánh xạ** và ánh xạ một **Nhóm** sang một **Vai trò** của workspace, hoặc sang
**Quản trị viên hệ thống**. Mỗi lần đăng nhập, OpenDBAPI tính lại vai trò của người dùng từ các nhóm của họ:

- workspace mà các nhóm của họ được ánh xạ tới nhận vai trò cao nhất trong các ánh xạ;
- tư cách thành viên đến từ một nhóm mà họ đã rời sẽ bị gỡ;
- vai trò cấp thủ công được giữ nguyên, trừ khi một ánh xạ cho workspace đó là **Được quản lý**: khi đó chỉ các ánh xạ
  quyết định;
- cờ quản trị viên hệ thống chỉ theo ánh xạ khi nhà cung cấp có ánh xạ **Quản trị viên hệ thống**.

Tên nhóm được so sánh không phân biệt hoa thường. Thay đổi có hiệu lực ở lần đăng nhập tiếp theo của từng người dùng.
