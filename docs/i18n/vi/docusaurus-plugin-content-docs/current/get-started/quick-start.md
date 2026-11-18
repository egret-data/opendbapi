---
sidebar_position: 1
---

# Bắt đầu nhanh

Chạy OpenDBAPI cùng một database cửa hàng mẫu nhỏ trên máy của bạn bằng Docker Compose, rồi đăng nhập.

Bạn cần Docker có plugin Compose và một bản clone của repository:

```bash
git clone https://github.com/egret-data/opendbapi.git
cd opendbapi
```

## Khởi động OpenDBAPI {#start-opendbapi}

```bash
docker compose -f deploy/quickstart/docker-compose.yml up -d
```

Lệnh này khởi động hai container:

- `postgres`: PostgreSQL 16, chứa dữ liệu riêng của OpenDBAPI (người dùng, workspace, endpoint, hoạt động).
- `opendbapi`: image `ghcr.io/egret-data/opendbapi:0.3.0`, phục vụ giao diện quản trị và các endpoint đã publish trên
  cổng 8520.

OpenDBAPI tạo schema ở lần khởi động đầu tiên. Chờ tới khi nó báo sẵn sàng:

```bash
curl -s http://localhost:8520/actuator/health/readiness
```

```json
{"status":"UP"}
```

## Nạp dữ liệu mẫu {#load-the-sample-data}

Bản bắt đầu nhanh có sẵn một cửa hàng nhỏ (6 khách hàng, 120 đơn hàng) và một tài khoản database chỉ đọc,
`shop_reader`, để OpenDBAPI dùng khi query:

```bash
docker compose -f deploy/quickstart/docker-compose.yml exec -T postgres \
  psql -U postgres -d opendbapi < deploy/quickstart/sample-data.sql
```

## Đăng nhập {#sign-in}

Mở [http://localhost:8520](http://localhost:8520) và đăng nhập bằng `admin` với mật khẩu `admin-change-me`.

![The sign-in page with Username and Password fields](/img/get-started/login.png)

:::warning
Bản bắt đầu nhanh dùng mật khẩu cố định đã công bố và một master key đã công bố. Chỉ dùng nó để thử OpenDBAPI. Với mọi
mục đích khác, hãy làm theo [Triển khai bằng Docker](../deploy/docker.md).
:::

## Tiếp theo {#next}

- [Publish endpoint đầu tiên](./first-endpoint.md) trên cửa hàng mẫu.
- Hoặc tạo sẵn một workspace demo đầy đủ (các endpoint, một workflow, một service account, một chính sách masking) bằng
  script dùng để chụp ảnh màn hình cho tài liệu này:

  ```bash
  cd docs && npm ci && node scripts/seed-demo.mjs
  ```

## Dừng và dọn dẹp {#stop-and-clean-up}

```bash
docker compose -f deploy/quickstart/docker-compose.yml down      # keep the data
docker compose -f deploy/quickstart/docker-compose.yml down -v   # remove it too
```
