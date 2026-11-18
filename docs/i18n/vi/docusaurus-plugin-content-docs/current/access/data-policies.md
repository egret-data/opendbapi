---
sidebar_position: 4
---

# Chính sách dữ liệu

Chính sách dữ liệu thay đổi những gì người gọi thấy mà không cần sửa query: **masking** che một phần giá trị, **row
filter** chỉ giữ lại các dòng người gọi được phép xem.

![The Masking tab of the shop datasource: customers.email masked as an email for everyone](/img/access/data-policies.png)

## Masking {#masks}

Một quy tắc masking thay giá trị của một cột trong mọi câu trả lời:

| Kiểu che | Kết quả |
|---|---|
| **Email** | `ada@example.com` → `a***@example.com` |
| **Số điện thoại** | `0901234123` → `090****123` |
| **Giữ phần đầu và cuối** | Giữ **Giữ ở đầu** ký tự đầu và **Giữ ở cuối** ký tự cuối, che phần còn lại |
| **Băm** | SHA-256 có salt: cùng đầu vào luôn cho cùng đầu ra, nên người gọi vẫn join và group được |
| **Luôn là null** | `null` |

Có hai chỗ đặt masking:

- **Trên datasource** (**Nguồn dữ liệu →** datasource đó **→ Che dữ liệu → Thêm quy tắc che**): nhập **Cột của bảng**,
  ví dụ `customers.email` hoặc `shop.customers.email`. Mọi endpoint trên datasource đều che cột này, ở bất cứ đâu nó xuất
  hiện trong kết quả và dưới bất kỳ alias nào, vì OpenDBAPI truy ngược từng cột kết quả về cột của bảng.
- **Trên endpoint** (tab **Truy cập** của endpoint): nhập **Cột kết quả** đúng như tên endpoint trả về.

**Không áp dụng cho** liệt kê các service account được bỏ qua, ví dụ một công cụ hỗ trợ khách hàng cần thấy email thật.

:::note
Khi không phân tích được SQL để truy cột (cú pháp riêng của từng hệ quản trị), masking trên datasource chỉ áp dụng theo
tên cột kết quả, và bước kiểm tra sẽ cảnh báo. Trong những query như vậy, đặt alias cho cột bị che bằng chính tên của nó.
:::

## Row filter {#row-filters}

Row filter đặt ở tab **Truy cập** của endpoint. Nó chỉ giữ các dòng có giá trị cột bằng một
[thuộc tính](./clients.md#attributes) của service account đang gọi. Ví dụ, row filter trên cột `country` với thuộc tính
`country` sẽ chỉ trả khách hàng Việt Nam cho account `reporting` có thuộc tính `country = VN`. Thuộc tính dạng danh sách
(`regions = north,south`) cho phép nhiều giá trị.

- Người gọi không có thuộc tính đó bị từ chối với `403 missing_attribute`.
- Row filter cần biết người gọi là ai, nên endpoint phải là **Riêng tư**.
- Cột phải nằm trong kết quả của query, và query sau khi lọc phải được database chấp nhận; bước kiểm tra xác nhận cả hai.

OpenDBAPI áp dụng bộ lọc bằng cách bọc query của endpoint, trước bước phân trang, nên trang và số đếm chỉ thấy các dòng
được phép.

## Chính sách áp dụng ở đâu {#where-policies-apply}

Chính sách dữ liệu áp dụng cho mọi cách endpoint trả lời: lời gọi thông thường, phân trang, stream, câu trả lời từ cache
(cache được tách theo người gọi khi chính sách khác nhau giữa các người gọi), mọi query trong
[script full mode](../endpoints/groovy-scripts.md#full-mode) hoặc [workflow](../endpoints/workflows.md), và các test chạy
dưới danh nghĩa một service account. Preview chạy không có người gọi, nên hiển thị masking áp dụng cho mọi người nhưng
không áp dụng row filter.

Thay đổi một chính sách sẽ xoá các câu trả lời đã cache của workspace trên mọi instance máy chủ.
