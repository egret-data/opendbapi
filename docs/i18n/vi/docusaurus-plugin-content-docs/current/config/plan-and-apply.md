---
sidebar_position: 2
---

# Plan và apply

**Plan** so sánh các file YAML với một workspace và liệt kê những gì sẽ thay đổi; **apply** làm cho workspace khớp với
file. Bạn có thể làm việc này trên giao diện, hoặc bằng [dòng lệnh](./cli-and-ci.md) từ CI.

![Cài đặt → Nhập & xuất: Xuất, Áp dụng YAML và Môi trường](/img/config/apply-yaml.png)

## Trên giao diện {#in-the-ui}

1. Mở **Cài đặt → Nhập & xuất**.
2. Trong **Áp dụng YAML**, chọn **File YAML**. Nếu tên datasource trong file khác với tên trong workspace này, chọn thêm
   một **Môi trường**.
3. Chọn các tuỳ chọn:
   - **Publish các endpoint đánh dấu live (sau khi test pass)**: publish từng endpoint có `state` là `live` khi test của
     nó pass, và vô hiệu hoá các endpoint đánh dấu `disabled`.
   - **Xoá những gì file không liệt kê**: xoá các endpoint, group, service account, grant và policy mà file không nhắc
     tới.
4. Bấm **Kiểm tra** để xem **Các thay đổi dự kiến**, rồi bấm **Áp dụng**.

Admin của workspace có thể plan và apply.

## Nội dung của một plan {#what-a-plan-shows}

```text
Workspace demo, environment prod

  · Datasource shop  (bound to datasource 'shop' in environment prod)
  ~ ServiceAccount reporting  (spec.description)

0 to create, 1 to update, 0 to delete, 9 unchanged.
Digest: 721faafb4703ea35fd3ad0cc4082c05c
```

`+` là tạo mới, `~` là cập nhật (kèm các trường khác nhau), `-` là xoá. **Digest** định danh plan: truyền nó vào
`apply --expected-digest` để từ chối apply nếu workspace đã thay đổi kể từ lúc plan được duyệt.

## Cách apply hoạt động {#how-apply-works}

- Mọi thứ được ghi trong một transaction: nếu có lỗi, không có gì thay đổi.
- Bản nháp của endpoint được cập nhật; người gọi vẫn nhận phiên bản live cho tới khi bản nháp được publish.
- Khi bật publish, mỗi endpoint `live` chạy [test](../endpoints/tests.md) của nó trước. Endpoint có test thất bại sẽ
  không được publish và được báo lại, phần còn lại vẫn được apply.
- Apply không bao giờ tạo hay xoá client và secret của client.

## Môi trường {#environments}

Một **môi trường** ánh xạ tên datasource dùng trong file sang datasource của workspace này. Nhờ đó cùng một file áp dụng
được cho dev, staging và prod, trong khi mỗi nơi giữ host và mật khẩu riêng.

1. Trong **Môi trường**, bấm **Thêm môi trường** và đặt tên (`prod`).
2. Bấm **Thêm ánh xạ**: với mỗi **Tên trong file**, chọn **Nguồn dữ liệu ở đây**.
3. Bấm **Lưu**.

Khi apply với môi trường đó, các tài liệu `Datasource` trong file được gắn vào datasource đã ánh xạ thay vì tạo mới hay
sửa chúng.

## Khoá workspace theo file cấu hình {#lock-a-workspace-to-its-files}

Khi workspace đã được quản lý từ Git, bật **Cài đặt → Chung → Quản lý bằng file cấu hình**. Từ đó endpoint và test của
nó, group, service account, grant và data policy chỉ thay đổi qua apply; sửa chúng trên giao diện
sẽ nhận `409 config_locked`. Kiểm tra, preview, chạy test, xoá cache, Try-it và thông tin xác thực của client vẫn dùng được. Datasource,
secret, thành viên và cài đặt cũng vậy, vì chúng thuộc về từng môi trường.

Đặt `opendbapi.ui.read-only=true` trên server để khoá mọi workspace.
