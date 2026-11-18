---
sidebar_position: 2
---

# Cài đặt endpoint

Tab **Cài đặt** của một endpoint quyết định cách nó được liệt kê, cách kết quả được phân trang và định dạng, cùng cache,
stream, giới hạn, quy tắc tham số và cảnh báo. Thay đổi có hiệu lực khi bạn publish.

![Tab Cài đặt của endpoint Customers với nhóm Endpoint và Phân trang](/img/endpoints/settings.png)

## Endpoint {#endpoint}

| Cài đặt | Tác dụng |
|---|---|
| **Tên** | Tên hiển thị trong danh sách |
| **Mô tả** | Hiện trong tài liệu API được sinh ra |
| **Phương thức** | Không đổi được khi đang chạy, vì người gọi phụ thuộc vào nó. Tắt endpoint để đổi |
| **Đường dẫn** | Không đổi được khi đang chạy, cùng lý do. Các đoạn `{name}` trở thành tham số path |
| **Truy cập** | **Riêng tư** cần token của client; **Công khai** trả lời bất kỳ ai |
| **Nhóm** | Một thư mục để sắp xếp danh sách, không thuộc URL |

## Phân trang {#pagination}

| Cài đặt | Tác dụng | Mặc định |
|---|---|---|
| **Phân trang** | Người gọi lấy từng trang một; OpenDBAPI thêm mệnh đề phân trang phù hợp với database | Tắt |
| **Kích thước trang mặc định** | Số dòng mỗi trang khi người gọi không truyền `size` | 20 |
| **Kích thước trang tối đa** | Giá trị `size` lớn nhất người gọi được yêu cầu | 500 |
| **Đếm tổng** | Chế độ offset: thêm một query `COUNT` mỗi lần gọi và trả về `meta.total`; khi tắt chỉ trả về `meta.hasNext` | Bật |
| **Chế độ** | **Offset** dùng `page` và `size`. **Keyset** trả về một cursor cho trang kế tiếp: ổn định khi có dữ liệu mới chèn vào và nhanh với các trang sâu, nhưng không nhảy thẳng tới trang N được | Offset |
| **Cột khoá** | Chế độ keyset: các cột kết quả dùng để sắp thứ tự trang; không được null và kết hợp lại phải duy nhất, nên hãy kết thúc bằng một cột id. Đánh dấu một khoá **Mới nhất / lớn nhất trước** để phân trang theo chiều giảm dần | — |

Khi bật phân trang, `page`, `size`, `after` và `format` là các tên tham số dành riêng.

```text
GET /api/demo/v1/customers?page=2&size=20       offset mode
GET /api/demo/v1/events?size=50&after=eyJr…     keyset mode: after = meta.nextCursor of the previous page
```

## Đầu ra {#output}

| Cài đặt | Tác dụng |
|---|---|
| **Dạng** | **Danh sách** (một mảng các dòng), **Một đối tượng** (dòng đầu tiên; không có dòng nào thì trả về `404 not_found`), hoặc **Giá trị đơn** (cột đầu tiên của dòng đầu tiên) |
| **Tên khoá** | **Giữ như trong SQL**, hoặc **camelCase**, chuyển `created_at` thành `createdAt` |
| **Kiểu giá trị** | Chuyển đổi giá trị của một cột: **Thập phân dạng chuỗi** (số tiền dạng chuỗi để JavaScript giữ đủ mọi chữ số), **Ngày ISO 8601**, **Epoch mili giây**, **Boolean (0/1)** |
| **Lồng dữ liệu** | Gom các dòng join phẳng thành đối tượng có collection: chọn cột **id**, sau đó tạo một collection cho mỗi tiền tố (ví dụ các cột `order_` vào `orders`) |

## Cache {#cache}

| Cài đặt | Tác dụng | Mặc định |
|---|---|---|
| **Cache kết quả** | Giữ các kết quả `GET` thành công trong bộ nhớ và trả lại chúng cho tới khi hết hạn; `meta.cached` báo cho người gọi biết | Tắt |
| **Giữ trong** | Số giây, tối đa 86 400 (một ngày) | 60 |
| **Dùng chung giữa các bên gọi** | Một mục cache cho mọi người gọi thay vì một mục cho mỗi service account. Không được phép khi data policy khiến kết quả khác nhau theo người gọi | Tắt |

**Xoá cache** xoá các kết quả đã cache của endpoint này. Các lần gọi thử (try-it) không bao giờ được cache. Kết quả cache
được giữ riêng trên từng instance server, tối đa 128 MB (`opendbapi.cache.max-bytes`).

## Stream {#streaming}

| Cài đặt | Tác dụng |
|---|---|
| **Cho phép stream** | Người gọi có thể yêu cầu `?format=ndjson` hoặc `?format=csv` (hoặc qua header `Accept`) để nhận các dòng ngay khi chúng được đọc, không phân trang |
| **Định dạng** | NDJSON, CSV, hoặc cả hai |
| **Số dòng stream tối đa** | Để trống thì bằng **Số dòng trả về tối đa**; tối đa 10 000 000 |

## Giới hạn {#limits}

| Cài đặt | Tác dụng | Mặc định |
|---|---|---|
| **Thời gian chờ** | Số giây; quá thời gian này query bị huỷ và người gọi nhận `504 query_timeout` | 30 |
| **Số dòng trả về tối đa** | Các dòng vượt quá bị cắt bỏ và `meta.truncated` được đặt | 10 000 |
| **Giới hạn tần suất** | Số lần gọi mỗi phút endpoint này trả lời, tính trên mọi người gọi; vượt quá sẽ nhận `429 rate_limited` kèm `Retry-After` | Không có |
| **Giao dịch** | Chạy mọi câu lệnh trong một transaction; bất kỳ lỗi nào cũng rollback toàn bộ | Tắt |

Giới hạn theo client và theo địa chỉ IP cho cả workspace nằm ở [Bảo mật](../workspace/security.md).

## Quy tắc tham số {#parameter-rules}

Các phép kiểm tra trên nhiều tham số, chạy trước query, viết bằng cùng ngôn ngữ biểu thức với `<if test>`. Mỗi quy tắc có
một **Thông báo cho bên gọi**. Quy tắc không thoả (hoặc không tính được) sẽ trả về `400 rule_failed` kèm thông báo đó. Tối
đa 20 quy tắc.

```text
from == null or to == null or from <= to       "from must be before to"
ids == null or ids.size() <= 100               "Pass at most 100 ids"
```

## Cảnh báo {#alerts}

**Cảnh báo khi lỗi** gửi lỗi server (5xx) tới các [kênh cảnh báo](../workspace/alerts.md) của workspace, mỗi lỗi tối đa
một lần trong vài phút.

## Đọc thêm {#further-reading}

- [Viết và publish endpoint](./write-and-publish.md)
- [Gọi endpoint](../access/calling-endpoints.md): envelope của response, phân trang và stream từ phía người gọi.
