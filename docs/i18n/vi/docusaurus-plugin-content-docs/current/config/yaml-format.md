---
sidebar_position: 1
---

# Định dạng YAML

Một workspace được xuất ra thành một chuỗi tài liệu YAML, mỗi đối tượng một tài liệu. Các tài liệu tham chiếu nhau bằng
tên thay vì id nội bộ. Nhờ vậy bạn có thể áp dụng cùng một file cho workspace khác hoặc bản cài đặt khác.

Xuất workspace bằng **Cài đặt → Nhập & xuất → Tải YAML**, hoặc bằng
[`opendbapi export`](./cli-and-ci.md). File xuất ra không bao giờ chứa mật khẩu, client secret, khoá ký và URL webhook.

## Tài liệu {#documents}

Mỗi tài liệu có `apiVersion: opendbapi/v1`, một `kind`, và `metadata` và/hoặc `spec`:

| Kind | Định danh bằng | Chứa |
|---|---|---|
| `Datasource` | `metadata.name` | Thông số kết nối, pool, `readOnly` và `passwordRef` (`secret:NAME`, `env:NAME` hoặc `vault:PATH#KEY`) |
| `Group` | `metadata.name` | Một thư mục chứa endpoint |
| `ServiceAccount` | `metadata.name` | Mô tả, `disabled`, thuộc tính. Client không được xuất: hãy tạo client riêng cho từng môi trường |
| `Endpoint` | `spec.method` + `spec.path` | Bản nháp: tham số, query, cấu hình và test; `metadata.state` (`draft`, `live` hoặc `disabled`) |
| `Grant` | service account của nó + endpoint hoặc group | Cho phép một service account gọi một endpoint (`endpoint: GET /v1/orders`) hoặc một group |
| `DataPolicy` | cột của nó + datasource hoặc endpoint | Một masking hoặc row filter |

## Ví dụ {#example}

Trích từ workspace demo:

```yaml
apiVersion: opendbapi/v1
kind: Datasource
metadata:
  name: shop
spec:
  dialect: POSTGRESQL
  host: postgres
  port: 5432
  database: opendbapi
  username: shop_reader
  properties:
    currentSchema: shop
  readOnly: true
  passwordRef: secret:shop-reader-password
---
apiVersion: opendbapi/v1
kind: Group
metadata:
  name: Customers
---
apiVersion: opendbapi/v1
kind: ServiceAccount
metadata:
  name: reporting
spec:
  description: Nightly reporting job
  attributes:
    country: VN
---
apiVersion: opendbapi/v1
kind: Endpoint
metadata:
  name: Customer
  group: Customers
  state: live
spec:
  method: GET
  path: "v1/customers/{id}"
  access: private
  datasource: shop
  params:
  - name: id
    in: path
    type: bigint
    required: true
  task:
    type: sql
    statements:
    - id: main
      template: "select id, name, email, country, tier from customers where id = #{id}"
  output:
    naming: camelCase
    shape: single
---
apiVersion: opendbapi/v1
kind: Grant
spec:
  serviceAccount: reporting
  group: Customers
---
apiVersion: opendbapi/v1
kind: DataPolicy
spec:
  kind: mask
  column: customers.email
  datasource: shop
  mask:
    type: email
```

Cấu hình nào bị bỏ qua sẽ nhận giá trị mặc định, nên một file viết tay có thể ngắn hơn nhiều so với file xuất.

Datasource kiểu [HTTP API](../datasources/http-apis.md) có `type: http` và các cấu hình nằm dưới `spec`. Endpoint của nó
dùng task `http`:

```yaml
apiVersion: opendbapi/v1
kind: Datasource
metadata:
  name: orders-api
spec:
  type: http
  baseUrl: https://api.example.com/v1
  auth: bearer
  headers:
    X-Tenant: acme
  readOnly: true
  passwordRef: secret:orders-api-token
---
apiVersion: opendbapi/v1
kind: Endpoint
metadata:
  name: Customer orders
spec:
  method: GET
  path: "v1/customers/{id}/orders"
  access: private
  datasource: orders-api
  params:
  - name: id
    in: path
    type: bigint
    required: true
  task:
    type: http
    request:
      method: GET
      path: "/customers/{id}/orders"
      query:
      - name: status
        value: "'open'"
      resultPath: /data
```

## Tách file {#splitting-files}

Bạn có thể để mỗi mảng hoặc mỗi endpoint một file: [`plan` và `apply`](./plan-and-apply.md) đọc nhiều file, hoặc mọi
file `*.yaml` và `*.yml` trong một thư mục, như một chuỗi duy nhất. Mật khẩu không bao giờ được đặt trong file; trường
`password` sẽ bị từ chối.
