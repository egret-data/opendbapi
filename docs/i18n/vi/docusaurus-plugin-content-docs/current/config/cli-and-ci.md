---
sidebar_position: 3
---

# Dòng lệnh và CI

Công cụ dòng lệnh `opendbapi` export, plan và apply từ terminal hoặc từ một pipeline CI. Nó có sẵn trong image
OpenDBAPI: `docker run ghcr.io/egret-data/opendbapi:0.3.0 <command>` chạy công cụ này thay vì server.

## Tạo API token {#create-an-api-token}

Công cụ dòng lệnh đăng nhập bằng một API token cá nhân.

1. Mở menu người dùng (chữ cái đầu tên bạn, góc trên bên phải) và chọn **API token**.
2. Bấm **Token mới**, nhập **Tên**, chọn **Workspace** (hoặc **Mọi workspace của tôi**) và thời điểm **Hết hạn** (30, 90
   hoặc 365 ngày).
3. Bấm **Tạo token** và sao chép token (`odb_…`). Token chỉ hiện đúng một lần này: hãy lưu nó vào phần secret của CI.

![Trang API token](/img/config/api-tokens.png)

Token làm được những gì bạn làm được, giới hạn trong workspace bạn đã chọn; apply cần vai trò **Quản trị viên**.
**Thu hồi** vô hiệu hoá token ngay lập tức. Khi không có token, công cụ dòng lệnh có thể đăng nhập như một người dùng cục
bộ bằng `OPENDBAPI_USER` và `OPENDBAPI_PASSWORD`.

## Lệnh {#commands}

```text
opendbapi export   Write the workspace as YAML (to --out, or standard output)
opendbapi plan     Show what apply would change
opendbapi apply    Make the workspace match the files
```

| Tuỳ chọn | Ý nghĩa |
|---|---|
| `--url URL` | URL gốc của OpenDBAPI (hoặc `OPENDBAPI_URL`) |
| `--workspace SLUG` | Workspace (hoặc `OPENDBAPI_WORKSPACE`) |
| `--file PATH` | Một file YAML; dùng lặp lại được |
| `--dir PATH` | Mọi file `*.yaml` / `*.yml` trong một thư mục |
| `--out PATH` | `export`: ghi vào đây thay vì standard output |
| `--env NAME` | [Môi trường](./plan-and-apply.md#environments) ánh xạ tên datasource |
| `--prune` | Xoá những gì file không liệt kê |
| `--publish` | Publish các endpoint đánh dấu `live` (sau khi test pass), vô hiệu hoá các endpoint đánh dấu `disabled` |
| `--expected-digest D` | `apply`: từ chối nếu plan không còn digest này |
| `--detailed-exitcode` | `plan`: thoát với mã 2 khi có thay đổi |

Mã thoát: `0` thành công; `1` có lỗi, hoặc có endpoint publish thất bại; `2` plan có thay đổi (khi dùng
`--detailed-exitcode`).

```bash
export OPENDBAPI_URL=https://opendbapi.example.com OPENDBAPI_WORKSPACE=sales OPENDBAPI_TOKEN=odb_...
docker run --rm -v "$PWD/api:/api" -e OPENDBAPI_URL -e OPENDBAPI_WORKSPACE -e OPENDBAPI_TOKEN \
  ghcr.io/egret-data/opendbapi:0.3.0 plan --dir /api --env prod --publish --detailed-exitcode
```

## GitHub Actions {#github-actions}

Plan khi có pull request, apply khi push lên `main`:

```yaml
name: api
on:
  pull_request:
    paths: [api/**]
  push:
    branches: [main]
    paths: [api/**]
jobs:
  opendbapi:
    runs-on: ubuntu-24.04
    env:
      OPENDBAPI_URL: https://opendbapi.example.com
      OPENDBAPI_WORKSPACE: sales
      OPENDBAPI_TOKEN: ${{ secrets.OPENDBAPI_TOKEN }}
    steps:
      - uses: actions/checkout@v4
      - name: plan
        if: github.event_name == 'pull_request'
        run: docker run --rm -v "$PWD/api:/api" -e OPENDBAPI_URL -e OPENDBAPI_WORKSPACE -e OPENDBAPI_TOKEN
          ghcr.io/egret-data/opendbapi:0.3.0 plan --dir /api --env prod --publish
      - name: apply
        if: github.event_name == 'push'
        run: docker run --rm -v "$PWD/api:/api" -e OPENDBAPI_URL -e OPENDBAPI_WORKSPACE -e OPENDBAPI_TOKEN
          ghcr.io/egret-data/opendbapi:0.3.0 apply --dir /api --env prod --publish
```
