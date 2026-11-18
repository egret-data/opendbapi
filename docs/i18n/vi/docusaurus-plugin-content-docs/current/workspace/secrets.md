---
sidebar_position: 2
---

# Secret

Secret là một giá trị có tên mà workspace lưu ở dạng mã hoá, ví dụ mật khẩu database hoặc URL webhook. Datasource và
kênh cảnh báo tham chiếu nó bằng `secret:NAME`, nên đổi giá trị một lần là mọi nơi dùng nó đều được cập nhật, và giá trị
không bao giờ xuất hiện trong bản export hay file cấu hình.

![The Secrets tab listing shop-reader-password and where it is used](/img/workspace/secrets.png)

Chỉ admin của workspace quản lý secret.

## Thêm secret {#add-a-secret}

1. Mở **Cài đặt → Secret** và bấm **Thêm secret**.
2. Nhập **Tên** (chữ thường, chữ số, `.`, `_` và `-`; không đổi tên được về sau), **Giá trị** và
   **Mô tả (không bắt buộc)** nếu muốn.
3. Bấm **Lưu**.

Giá trị chỉ ghi được: không ai đọc lại được, chỉ có thể thay bằng **Đổi**. Giá trị được mã hoá bằng AES-256-GCM với
master key của máy chủ.

## Dùng secret {#use-a-secret}

Nhập `secret:NAME` thay cho giá trị:

- trong **Mật khẩu** của datasource (xem [Datasource](../datasources/datasources.md));
- trong **URL webhook** của kênh cảnh báo (xem [Cảnh báo](./alerts.md)).

**Đang dùng bởi** liệt kê những gì đang tham chiếu tới từng secret. Không xoá được secret còn đang được dùng. Đổi một
secret mà datasource đang dùng sẽ đóng các kết nối của datasource đó trên mọi instance máy chủ, nên mật khẩu mới có hiệu
lực ngay.

## Các nguồn giá trị khác {#other-places-a-value-can-come-from}

| Tham chiếu | Đọc từ |
|---|---|
| `secret:NAME` | Secret của workspace này |
| `env:NAME` | Biến môi trường (hoặc Spring property) của máy chủ OpenDBAPI |
| `vault:PATH#KEY` | [HashiCorp Vault](../deploy/vault.md), trong các path máy chủ cho phép đối với workspace |
