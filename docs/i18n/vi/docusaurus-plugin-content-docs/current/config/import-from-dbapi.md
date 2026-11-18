---
sidebar_position: 4
---

# Nhập từ DBAPI

Chuyển API từ DBAPI sang một workspace OpenDBAPI bằng các file mà DBAPI xuất ra.

1. Trong DBAPI, xuất những gì bạn muốn chuyển:

   | File | Vị trí trong DBAPI |
   |---|---|
   | `api_config.json` | API list → **Export API** |
   | `group.json` | **Export API groups** |
   | `datasource.json` | **Export datasources** |

2. Trong OpenDBAPI, mở **Cài đặt → Nhập & xuất** và trong **Nhập từ DBAPI**, chọn các file.
3. Bấm **Kiểm tra**. **Báo cáo nhập** liệt kê những gì sẽ được tạo, bị bỏ qua, và những gì không chuyển sang được. Lúc
   này chưa có gì được ghi.
4. Bấm **Nhập**.

Mỗi loại đối tượng được xử lý như sau:

- API trở thành bản nháp endpoint: hãy xem lại, test và publish từng cái.
- Group được khớp theo tên và được tạo nếu chưa có.
- Datasource được tạo lại từ JDBC URL của nó, ở chế độ chỉ đọc và không có mật khẩu. Hãy đặt mật khẩu (hoặc một
  [secret](../workspace/secrets.md)) cho từng datasource trước khi test.

Những tính năng của DBAPI mà OpenDBAPI không chuyển sang (plugin, quy tắc tham số, executor không phải SQL, thay thế văn
bản `${}`) được liệt kê trong báo cáo, nên không có gì bị bỏ đi âm thầm. Hãy viết lại `${}` thành tham số bind `#{}` hoặc
thẻ `<orderBy>` (xem [Viết và publish endpoint](../endpoints/write-and-publish.md#conditional-sql)).
