---
sidebar_position: 4
---

# Phiên bản major và ngừng khuyến nghị

Publish một phiên bản mới sẽ thay thế những gì người gọi nhận được ở cùng URL. Khi thay đổi có thể làm hỏng người gọi (bỏ
một cột, đổi kiểu dữ liệu), hãy publish nó thành **phiên bản major mới** ở path mới, giữ phiên bản cũ tiếp tục chạy, và
thông báo ngày kết thúc bằng **ngừng khuyến nghị** (deprecation).

![Tab Phiên bản với bảng Các phiên bản major: v1 đã ngừng khuyến nghị với ngày dừng phục vụ, v2 đang chạy](/img/endpoints/versions.png)

## Cảnh báo thay đổi phá vỡ tương thích {#breaking-change-warning}

Khi bạn validate hoặc publish, OpenDBAPI so sánh các cột kết quả của bản nháp với phiên bản đang chạy. Một cột bị bỏ hoặc
bị đổi kiểu sẽ sinh cảnh báo `breaking_change`, ví dụ:

```text
Compared with live version 3, this drops column 'email'; callers may break. Consider a new major version instead.
```

Cảnh báo này không chặn việc publish.

## Tạo phiên bản major mới {#create-a-new-major-version}

1. Trên endpoint, mở menu thao tác cạnh nút **Xuất bản** và chọn **Tạo phiên bản major mới…**.
2. Để trống **Path của phiên bản mới** để dùng `v2/…` (`v1/orders` thành `v2/orders`, `orders` thành `v2/orders`), hoặc
   nhập một path khác.
3. Bấm **Tạo bản draft**.

Endpoint mới là một bản nháp sao chép, tên `… v2`, thuộc cùng **lineage**. Sửa nó, publish nó, và hai phiên bản chạy song
song. Chúng là hai endpoint riêng biệt: grant, kiểm thử và data policy được đặt cho từng endpoint. Khi lineage có từ hai
major trở lên, tab **Phiên bản** liệt kê các major đó.

## Ngừng khuyến nghị phiên bản cũ {#deprecate-the-old-version}

1. Trên endpoint cũ, chọn **Ngừng khuyến nghị…** trong menu thao tác.
2. Có thể đặt **Ngày dừng phục vụ (không bắt buộc)**: thời điểm endpoint sẽ ngừng trả lời.
3. Bấm **Ngừng khuyến nghị**.

Endpoint vẫn tiếp tục trả lời, nhưng mọi response giờ có thêm:

```text
Deprecation: @1794930769
Sunset: Wed, 30 Jun 2027 00:00:00 GMT
Link: </api/demo/v2/customers>; rel="successor-version"
```

- `Deprecation` (RFC 9745) là thời điểm endpoint bị ngừng khuyến nghị, dạng `@` cộng số giây tính từ năm 1970.
- `Sunset` (RFC 8594) chỉ có khi bạn đặt ngày.
- `Link` trỏ tới major mới nhất đang chạy của lineage.

Endpoint cũng được đánh dấu `deprecated` trong tài liệu OpenAPI của workspace. **Bỏ ngừng khuyến nghị** gỡ các header này.

:::note
OpenDBAPI không tự ngừng phục vụ vào ngày sunset. Hãy tự tắt endpoint khi người gọi đã chuyển sang phiên bản mới.
:::

## Trong file cấu hình {#in-configuration-files}

Lineage và trạng thái ngừng khuyến nghị nằm trong metadata YAML của endpoint: `majorOf` chỉ tới major đầu tiên của lineage
bằng route của nó, nên dùng được ở mọi môi trường.

```yaml
metadata:
  name: Customers v2
  majorOf: GET /v1/customers
  major: 2
---
metadata:
  name: Customers
  deprecated: true
  sunset: 2027-06-30T00:00:00Z
```
