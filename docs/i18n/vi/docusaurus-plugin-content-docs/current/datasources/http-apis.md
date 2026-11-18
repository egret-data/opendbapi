---
sidebar_position: 2
---

# HTTP API

Datasource HTTP là một REST API khác mà endpoint gọi thay cho cơ sở dữ liệu. Endpoint trên datasource này gửi một
request dựng từ các tham số của nó, rồi trả về JSON nhận được dưới dạng các dòng. Dùng nó để đặt một API sẵn có sau
kiểm soát truy cập, rate limit, masking, cache và nhật ký hoạt động của OpenDBAPI, hoặc để kết hợp API đó với các cơ
sở dữ liệu của bạn trong một [workflow](../endpoints/workflows.md).

## Kết nối API {#connect-an-api}

1. Mở **Nguồn dữ liệu** và bấm **Nguồn dữ liệu mới**.
2. Đặt **Loại** là **HTTP API** và điền các cài đặt bên dưới.
3. Bấm **Kiểm tra kết nối**: nút này gửi `GET` tới **URL gốc** và báo lại status. 401 hoặc 403 nghĩa là API từ chối
   thông tin đăng nhập; 5xx nghĩa là API đang lỗi. Các phản hồi khác, kể cả 404, nghĩa là API truy cập được.
4. Bấm **Tạo nguồn dữ liệu**.

### Kết nối {#connection}

| Cài đặt | Ý nghĩa |
|---|---|
| **URL gốc** | `http://` hoặc `https://`, một host, và path tuỳ chọn, ví dụ `https://api.example.com/v1`. Không có user name, query hay fragment |
| **Header** | Gửi kèm mọi request, ví dụ `X-Tenant: acme`. Header được lưu dạng văn bản thường, nên `Authorization`, `Cookie` và các thông tin đăng nhập khác bị từ chối ở đây |
| **Thời gian chờ kết nối** | Thời gian chờ kết nối, tính bằng mili giây (250 đến 60000). Cài đặt **Thời gian chờ** của endpoint giới hạn toàn bộ request |

### Xác thực {#authentication}

| **Xác thực** | Gửi | Trường bí mật |
|---|---|---|
| **Không** | Không gửi gì | — |
| **Bearer token** | `Authorization: Bearer <token>` | **Token** |
| **Basic (tên đăng nhập và mật khẩu)** | `Authorization: Basic …` với **Tên đăng nhập** | **Mật khẩu** |
| **API key trong header** | Key trong **Tên header**, ví dụ `X-API-Key` | **API key** |

Giá trị bí mật được lưu giống mật khẩu cơ sở dữ liệu: được mã hoá, hoặc dưới dạng tham chiếu `secret:NAME`, `env:NAME`
hay `vault:PATH#KEY`. Nó chỉ được gửi tới host của URL gốc.

### An toàn {#safety}

- **Chỉ đọc**: endpoint chỉ được gửi request `GET`; các method khác bị từ chối lúc kiểm tra và khi được gọi.
- Redirect không được theo: phản hồi `3xx` là lỗi, nên request không thể bị chuyển tiếp sang host khác.
- Host được kiểm tra ở mỗi request theo danh sách host mà datasource được phép truy cập (xem
  [Datasource](./datasources.md#which-hosts-are-allowed)).
- Chỉ đọc tối đa 10 MB response.

## Viết request {#write-the-request}

Khi datasource của endpoint là HTTP API, tab **Truy vấn** hiển thị request thay cho SQL. Chọn datasource này sẽ chuyển
**Ngôn ngữ** sang **Request HTTP**.

| Trường | Ý nghĩa |
|---|---|
| **Yêu cầu** | Method (`GET`, `POST`, `PUT`, `PATCH` hoặc `DELETE`) và path phía sau URL gốc, ví dụ `/orders/{id}` |
| **Truy vấn** | Các trường query string: một tên và một giá trị |
| **JSON body** | Các trường của một JSON object gửi làm body; không dùng cho `GET`. Để trống nếu không gửi body |
| **Dòng dữ liệu ở** | Một [JSON Pointer](https://datatracker.ietf.org/doc/html/rfc6901) trỏ tới các dòng trong response, ví dụ `/data/items`; để trống để lấy toàn bộ response |

Các giá trị, và `{…}` trong path, là biểu thức trên tham số của endpoint, giống như trong `<if test>`: tên tham số như
`status`, một trường như `customer.id`, hoặc văn bản trong dấu nháy như `'open'`.

- Giá trị trong path được mã hoá, nên không thể chèn `/`, `?` hay `&` vào URL.
- Giá trị query là list được gửi thành trường lặp lại (`tag=a&tag=b`). Giá trị null bị bỏ qua.
- **Phát hiện từ request**, phía trên danh sách tham số, thêm các tên tham số mà request dùng.

Ví dụ, với URL gốc `https://api.example.com/v1`, request `GET /customers/{id}/orders`, trường query `status` ←
`status` và **Dòng dữ liệu ở** là `/data`, một lời gọi với `id=7&status=open` sẽ gửi:

```text
GET https://api.example.com/v1/customers/7/orders?status=open
```

và trả về mảng ở `data` trong response.

### Dòng dữ liệu từ response {#rows-from-the-response}

| Tại **Dòng dữ liệu ở** | Các dòng |
|---|---|
| Một mảng | Mỗi phần tử một dòng. Các trường của object là cột; giá trị khác thành cột `value` |
| Một object | Một dòng |
| `null`, hoặc body rỗng | Không có dòng nào |
| Không có gì | Lỗi `upstream_failed` |

Sau đó các dòng đi qua cài đặt của endpoint giống kết quả query: mask, ánh xạ [đầu ra](../endpoints/settings.md#output)
(tên, cấu trúc, kiểu), **Số dòng trả về tối đa** và cache.

## Lời gọi kết thúc thế nào {#how-calls-end}

| API trả về | Người gọi nhận |
|---|---|
| 2xx kèm JSON | 200 kèm các dòng |
| 404 | 404 `not_found` |
| Status khác, redirect, hoặc body không phải JSON | 502 `upstream_failed` |
| Không phản hồi trong timeout của endpoint | 504 `query_timeout` |

## Giới hạn {#limits}

- **Xem trước** chỉ gửi request `GET`: API không thể rollback `POST`, `PUT`, `PATCH` hay `DELETE`. Hãy publish endpoint
  rồi gọi nó, hoặc dùng **Chạy thử**, để gửi các request này.
- Không có phân trang, streaming hay giao dịch: hãy chuyển tham số phân trang sang API trong mục **Truy vấn**.
- Column mask áp dụng cho các dòng; row filter cần SQL, nên endpoint chịu row filter sẽ bị từ chối.
- Tab **Bảng** không hiển thị với HTTP API.
