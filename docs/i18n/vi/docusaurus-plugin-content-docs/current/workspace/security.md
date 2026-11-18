---
sidebar_position: 4
---

# Bảo mật

Mỗi workspace có một firewall theo địa chỉ người gọi và rate limit theo client và theo địa chỉ. Cả hai áp dụng cho mọi
người gọi endpoint của workspace, dù endpoint là public hay private.

![The Security tab with the firewall and rate limits](/img/workspace/security.png)

Mở **Cài đặt → Bảo mật**. Admin của workspace có thể thay đổi các thiết lập này.

## Firewall {#firewall}

| Thiết lập | Tác dụng |
|---|---|
| **Chế độ tường lửa** | **Tắt**, **Danh sách cho phép** (chỉ địa chỉ trong danh sách được gọi) hoặc **Danh sách chặn** (địa chỉ trong danh sách không được gọi) |
| **Địa chỉ** | Mỗi dòng một địa chỉ IP hoặc dải CIDR, ví dụ `10.0.0.0/8` hoặc `2001:db8::/32` |

Lời gọi bị từ chối nhận `403 ip_blocked`. Nếu chạy sau load balancer hoặc ingress, đặt
`opendbapi.serving.trusted-proxies` để OpenDBAPI đọc địa chỉ người gọi từ `X-Forwarded-For`; nếu không, mọi lời gọi sẽ
trông như đến từ proxy.

## Rate limit {#rate-limits}

| Thiết lập | Tác dụng |
|---|---|
| **Theo client** | Số lời gọi mỗi phút cho từng OAuth2 client gọi endpoint private. Để trống = không giới hạn |
| **Theo địa chỉ IP** | Số lời gọi mỗi phút cho từng địa chỉ gọi endpoint public. Để trống = không giới hạn |

Lời gọi vượt mức nhận `429 rate_limited` kèm header `Retry-After`. Từng endpoint cũng có thể có giới hạn riêng (xem
[Cài đặt endpoint](../endpoints/settings.md#limits)).

Mọi instance áp dụng thay đổi trong vài giây. Giới hạn được đếm riêng trên từng instance, trừ khi đặt
`opendbapi.rate-limit.shared=true` để đếm chung trong PostgreSQL cho mọi instance; Helm chart bật sẵn thiết lập này.
