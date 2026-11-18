---
sidebar_position: 5
---

# Hoạt động và audit log

**Hoạt động** hiển thị các lời gọi tới endpoint đã publish của workspace. **Audit log** ghi lại ai đã thay đổi gì.

## Hoạt động {#activity}

Mở **Hoạt động** trên thanh bên.

![The Activity page: requests, error rate, p95 latency, active clients and requests per day](/img/activity/activity.png)

- Các ô số liệu hiển thị **Lời gọi**, **Tỉ lệ lỗi**, **Độ trễ p95** và **Client đang hoạt động** cho khoảng thời gian đã
  chọn: **24 giờ qua**, **7 ngày qua** hoặc **30 ngày qua**.
- **Endpoint gọi nhiều nhất**, **Lỗi thường gặp nhất**, **Endpoint chậm nhất**, **Client gọi nhiều nhất** và
  **Địa chỉ gọi nhiều nhất** cho biết lời gọi và sự cố đến từ đâu.
- **Lời gọi** liệt kê từng lời gọi với mã trạng thái, client, thời gian xử lý, số dòng và mã lỗi. Lọc theo endpoint hoặc
  mã trạng thái, hoặc dán **request id** lấy từ một lỗi để tìm đúng lời gọi đó.
- **Gồm cả Try-it** thêm các lời gọi thực hiện từ tab **Chạy thử** của trình soạn endpoint.

Lời gọi được giữ 30 ngày (`opendbapi.access-log.retention-days`). Query string và request body không được lưu.

## Audit log {#audit-log}

**Cài đặt → Nhật ký kiểm toán** liệt kê mọi thay đổi trong workspace: endpoint được lưu, publish và rollback, datasource,
service account, client và grant, thành viên, secret, chính sách và các lần áp dụng cấu hình, kèm ai thực hiện, lúc nào
và từ địa chỉ nào. Trang này dành cho admin của workspace.

Quản trị viên hệ thống còn có **Quản trị → Nhật ký kiểm toán**: các lần đăng nhập và mọi thay đổi về workspace, người
dùng, thành viên và identity provider.
