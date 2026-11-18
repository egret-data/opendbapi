---
sidebar_position: 2
---

# Khái niệm

Một số thành phần tạo nên OpenDBAPI, và cách chúng liên kết với nhau.

## Workspace {#workspace}

Mỗi workspace thuộc về một nhóm và có riêng datasource, endpoint, service account, secret, chính sách và hoạt động.
Các workspace không dùng chung thứ gì, và row-level security của PostgreSQL ngăn dữ liệu của workspace này lọt vào query
của workspace khác. **Slug** của workspace nằm trong URL của mọi endpoint:

```text
https://opendbapi.example.com/api/{workspace}/{path}
```

Mọi người tham gia workspace với tư cách **thành viên** và có một vai trò; xem
[Thành viên và vai trò](../workspace/members-and-roles.md).

## Datasource {#datasource}

Database mà endpoint query tới (PostgreSQL, MySQL, MariaDB, SQL Server, Oracle, Trino, StarRocks, Apache Doris hoặc
ClickHouse), hoặc một [HTTP API](../datasources/http-apis.md) mà endpoint gửi request tới. Datasource lưu cấu hình kết
nối và một mật khẩu (đã mã hóa, hoặc tham chiếu tới một [secret](../workspace/secrets.md)). Đánh dấu nó là **chỉ đọc**
khi các endpoint của nó không bao giờ được sửa dữ liệu. Xem [Datasource](../datasources/datasources.md).

## Endpoint {#endpoint}

Một query cùng URL, tham số, dạng response, cache và các giới hạn của nó. Mỗi endpoint có một **bản nháp** để bạn sửa
và các **phiên bản** đánh số, cố định, để bạn publish:

| Trạng thái | Ý nghĩa | Trả lời lời gọi |
|---|---|---|
| **Bản nháp** | Đã tạo, chưa từng publish | Không |
| **Đang chạy** | Có một phiên bản đã publish. Header hiển thị `Live · v2`, và **Thay đổi chưa xuất bản** khi bản nháp mới hơn | Có |
| **Đã tắt** | Tạm ngừng phục vụ; vẫn giữ phiên bản đang chạy và các grant, nên **Bật** sẽ khôi phục đúng như trước | Không |
| **In the trash** | Đã xóa mềm; khôi phục để sửa lại | Không |

Người gọi chỉ thấy phiên bản đã publish. Lưu bản nháp không thay đổi gì với họ cho tới khi bạn nhấn **Xuất bản**.
Xem [Endpoint](../endpoints/write-and-publish.md).

## Service account, client và grant {#service-account-client-and-grant}

Chương trình gọi các endpoint **riêng tư** bằng token. Ba thứ quyết định ai được gọi endpoint nào:

- **Service account** là một định danh cho máy, ví dụ `reporting` hoặc `partner-acme`.
- **Client** là một cặp `client_id` và `client_secret` thuộc về một service account. Chương trình đổi cặp này lấy một
  token ngắn hạn tại `POST /oauth2/token`.
- **Grant** cho phép một service account gọi một endpoint, hoặc mọi endpoint trong một nhóm. Không có grant, lời gọi
  của nó nhận `403 not_granted`.

Endpoint **công khai** không cần token và trả lời bất kỳ ai truy cập được server. Xem
[Client và token](../access/clients.md).

## Chính sách dữ liệu {#data-policies}

Các quy tắc thay đổi những gì người gọi thấy, theo cột hoặc theo dòng: **masking** (hiển thị `a***@example.com` thay vì
email thật) và **row filter** (một service account chỉ thấy các dòng thuộc quốc gia của mình). Xem
[Chính sách dữ liệu](../access/data-policies.md).

## Cấu hình dưới dạng code {#configuration-as-code}

Một workspace được xuất ra thành các tài liệu YAML tham chiếu lẫn nhau theo tên. Lưu file trong Git rồi apply nó vào
một workspace hoặc một bản cài đặt khác; một **môi trường** (environment) ánh xạ tên datasource trong file sang datasource của
workspace đó. Xem [Cấu hình dưới dạng code](../config/yaml-format.md).
