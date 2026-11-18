---
sidebar_position: 3
---

# Kiểm thử

Các phép kiểm tra được lưu cho một endpoint: giá trị tham số và kết quả trả về phải trông thế nào. Chạy chúng trước mỗi lần
publish để phát hiện query bị hỏng, không trả về gì, hoặc đã đổi cột.

![Tab Kiểm thử của endpoint Customers với kiểm thử "gold customers"](/img/endpoints/tests.png)

## Thêm kiểm thử {#add-a-test}

1. Mở tab **Kiểm thử** của endpoint và bấm **Thêm kiểm thử**.
2. Đặt **Tên**, điền các **Tham số** dùng khi chạy, và chọn **Gọi với tư cách**: **Không có client (như xem trước)**, hoặc
   một service account. Khi đó grant, quy tắc và data policy của service account đó được áp dụng.
3. Trong **Kỳ vọng**, chỉ điền những gì cần kiểm tra; để trống một kỳ vọng thì nó được bỏ qua:

| Kỳ vọng | Đạt khi |
|---|---|
| **Trạng thái** | HTTP status khớp (mặc định là 200) |
| **Số dòng tối thiểu** / **Số dòng tối đa** | Số dòng nằm trong khoảng |
| **Kiểm tra JSONPath** | Một path vào `{data, meta}`, ví dụ `$.data[0].tier`, thoả **Phải tồn tại**, **Phải không tồn tại**, hoặc bằng một giá trị (**Chỉ giá trị**) |
| **Cùng các cột kết quả với phiên bản đang chạy** | Tên các cột kết quả trùng với phiên bản đang chạy, nên thay đổi sẽ không làm hỏng người gọi |
| **Trong vòng (ms)** | Lần gọi mất không quá khoảng thời gian này |

4. Bấm **Lưu**.

## Chạy kiểm thử {#run-tests}

**Chạy tất cả** chạy mọi kiểm thử của endpoint; **Chạy** chạy một kiểm thử. Kiểm thử chạy trên **bản nháp đã lưu**, trong
một transaction luôn được rollback, nên kiểm thử một endpoint `POST` không thay đổi gì. Hãy lưu trước: thay đổi chưa lưu
không được kiểm thử.

Mỗi kiểm thử hiện **Đạt**, **Thất bại** kèm lý do, hoặc **Chưa chạy**, cùng thời điểm chạy gần nhất.

## Bắt buộc kiểm thử đạt mới được publish {#require-passing-tests-to-publish}

Đặt `opendbapi.publish.require-tests-pass=true` (biến môi trường `OPENDBAPI_PUBLISH_REQUIRE_TESTS_PASS=true`) để từ chối
**Xuất bản** với `409 tests_failed` khi còn kiểm thử nào của endpoint thất bại. [Cấu hình dạng
code](../config/plan-and-apply.md) luôn chạy kiểm thử của endpoint trước khi publish nó.

## Kiểm thử trong file cấu hình {#tests-in-configuration-files}

Kiểm thử nằm trong tài liệu YAML của endpoint, với service account được ghi theo tên:

```yaml
tests:
  - name: gold customers
    serviceAccount: reporting
    params: {tier: gold}
    expect: {status: 200, rowCount: {min: 1}}
```
