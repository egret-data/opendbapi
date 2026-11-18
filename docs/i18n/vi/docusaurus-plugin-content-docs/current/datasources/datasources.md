---
sidebar_position: 1
---

# Datasource

Datasource là một cơ sở dữ liệu mà endpoint truy vấn. Admin của workspace kết nối datasource; tác giả chọn một
datasource cho mỗi endpoint. Nếu muốn gọi một REST API khác, xem [HTTP API](./http-apis.md).

![The shop datasource: PostgreSQL, read only, connected](/img/datasources/detail.png)

## Kết nối cơ sở dữ liệu {#connect-a-database}

1. Mở **Nguồn dữ liệu** và bấm **Nguồn dữ liệu mới**.
2. Điền các cài đặt bên dưới, bấm **Kiểm tra kết nối**, và khi thấy **Đã kết nối.** thì bấm **Tạo nguồn dữ liệu**.

### Kết nối {#connection}

| Cài đặt | Ý nghĩa |
|---|---|
| **Tên** | Tên mà endpoint và mọi người dùng để chỉ datasource; duy nhất trong workspace |
| **Mô tả** | Không bắt buộc |
| **Loại cơ sở dữ liệu** | PostgreSQL, MySQL, MariaDB, SQL Server, Oracle, Trino, StarRocks, Apache Doris hoặc ClickHouse; driver cho các loại này có sẵn |
| **Host** | Tên host hoặc địa chỉ IP, không kèm cổng |
| **Cổng** | Để trống để dùng cổng mặc định của loại cơ sở dữ liệu |
| **Cơ sở dữ liệu** | Cơ sở dữ liệu cần kết nối. Với Oracle là service name, ví dụ `ORCLPDB1`; với Trino là **Catalog** (xem [bên dưới](#database-types)) |
| **Thuộc tính driver** | Chỉ những thuộc tính an toàn mới được đưa ra, ví dụ `currentSchema` cho PostgreSQL |

### Thông tin đăng nhập {#credentials}

| Cài đặt | Ý nghĩa |
|---|---|
| **Tên đăng nhập** | Nên dùng tài khoản chỉ đọc được những gì endpoint cần |
| **Mật khẩu** | Chỉ không bắt buộc với Trino. Được mã hoá bằng master key của máy chủ. Hoặc nhập `secret:NAME` để dùng [secret được quản lý](../workspace/secrets.md), `env:NAME` để đọc từ biến môi trường của máy chủ, hoặc `vault:PATH#KEY` cho [HashiCorp Vault](../deploy/vault.md) |

Khi sửa, để trống **Mật khẩu** để giữ mật khẩu đã lưu.

### Pool {#pool}

| Cài đặt | Mặc định |
|---|---|
| **Số kết nối tối đa** | Cho mỗi instance máy chủ, từ 1 đến 50 |
| **Số kết nối rảnh được giữ** | 0 nghĩa là chỉ mở kết nối khi cần |
| **Thời gian chờ kết nối** | Thời gian một request chờ có kết nối rảnh, tính bằng mili giây |

### An toàn {#safety}

**Chỉ đọc** từ chối `INSERT`, `UPDATE`, `DELETE` và DDL ở mọi endpoint trên datasource, cả lúc kiểm tra lẫn khi script
chạy. Kết nối cũng được mở ở chế độ chỉ đọc nếu cơ sở dữ liệu hoặc driver hỗ trợ:

| Loại cơ sở dữ liệu | Cách kết nối từ chối lệnh ghi |
|---|---|
| PostgreSQL, MySQL, MariaDB | Session chỉ đọc |
| Trino | Mọi câu lệnh chạy trong một giao dịch chỉ đọc |
| StarRocks, Apache Doris | Driver từ chối các câu lệnh thay đổi dữ liệu hoặc schema; bản thân máy chủ không có session chỉ đọc |
| ClickHouse | Cài đặt `readonly = 2` |
| SQL Server, Oracle | Không áp dụng trên kết nối |

Một tài khoản cơ sở dữ liệu chỉ có quyền đọc vẫn là bảo đảm mạnh hơn, và là bảo đảm duy nhất với SQL Server và Oracle.

## Các loại cơ sở dữ liệu {#database-types}

Phần lớn các loại hoạt động giống nhau. Các loại sau có điểm khác:

### Trino {#trino}

- **Catalog**: catalog cần truy vấn, ví dụ `hive`, hoặc `catalog.schema` để đặt schema đó làm mặc định. Query vẫn có
  thể ghi đầy đủ tên catalog khác, ví dụ `iceberg.sales.orders`.
- **Mật khẩu**: để trống khi Trino chạy không xác thực. Trino chỉ nhận mật khẩu qua TLS, nên khi có mật khẩu, hãy thêm
  thuộc tính driver `SSL` = `true`.
- **Bảng** liệt kê các schema của catalog đã chọn.
- Phân trang dùng `OFFSET … FETCH NEXT … ROWS ONLY`, dạng mà Trino chấp nhận. Lệnh ghi trong giao dịch tuỳ vào
  connector; nhiều connector (Iceberg, Delta Lake, memory) chỉ nhận lệnh ghi bên ngoài giao dịch, nên preview không
  chạy được chúng.

### StarRocks và Apache Doris {#starrocks-and-apache-doris}

- Kết nối qua giao thức MySQL, mặc định ở cổng 9030 (cổng query của frontend).
- **Cơ sở dữ liệu**: một database, hoặc `catalog.database` cho external catalog.
- Hai hệ này nhận `ROLLBACK` nhưng vẫn giữ các dòng đã ghi, nên OpenDBAPI coi chúng là không có giao dịch; xem bên
  dưới.

### ClickHouse {#clickhouse}

- Kết nối qua HTTP, mặc định ở cổng 8123; đặt thuộc tính driver `ssl` là `true` để dùng HTTPS (thường ở cổng 8443).
- **Bảng** liệt kê các database của ClickHouse dưới dạng schema.
- ClickHouse không có giao dịch; xem bên dưới.

### Cơ sở dữ liệu không có giao dịch {#databases-without-transactions}

Trên StarRocks, Apache Doris và ClickHouse, lệnh ghi không thể hoàn tác, vì vậy:

- **Xem trước** chạy query nhưng từ chối các câu lệnh thay đổi dữ liệu, với `preview_cannot_roll_back`. Hãy publish
  endpoint và gọi nó để chạy các lệnh này.
- Endpoint không thể chạy trong **Giao dịch**; bước kiểm tra báo `transaction_unsupported`.
- Dòng **Loại cơ sở dữ liệu** trong form datasource có ghi chú điều này.

## Bảng và mức sử dụng {#tables-and-usage}

- **Bảng** cho phép duyệt schema, bảng và cột của datasource để hỗ trợ viết query.
- **Sử dụng** liệt kê các endpoint đang dùng datasource. Datasource còn được dùng thì không xoá được.
- **Che dữ liệu** chứa [chính sách dữ liệu](../access/data-policies.md) áp dụng cho mọi endpoint trên datasource.

![The Tables tab listing the shop schema's tables](/img/datasources/tables.png)

## Những host được phép {#which-hosts-are-allowed}

Các địa chỉ metadata của cloud (như `169.254.169.254`) luôn bị từ chối. Người vận hành có thể chặn thêm host bằng
`opendbapi.datasources.extra-denied-hosts`, hoặc chỉ cho phép một danh sách bằng `opendbapi.datasources.allowed-hosts`;
xem [Cấu hình](../reference/configuration.md).

## Thay đổi datasource {#changing-a-datasource}

Lưu cài đặt mới, hoặc thay đổi [secret](../workspace/secrets.md) mà mật khẩu của datasource tham chiếu, sẽ đóng
connection pool của nó trên mọi instance máy chủ; query tiếp theo sẽ kết nối lại với cài đặt mới.
