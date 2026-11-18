---
sidebar_position: 3
---

# Cảnh báo

Cảnh báo gửi tin nhắn tới Slack, Microsoft Teams, Google Chat hoặc bất kỳ webhook nào khi một endpoint đã publish gặp lỗi
máy chủ.

![The Alerts tab with one alert channel](/img/workspace/alerts.png)

## Thêm kênh cảnh báo {#add-an-alert-channel}

1. Mở **Cài đặt → Cảnh báo** và bấm **Thêm kênh**.
2. Nhập **Tên**, chọn **Loại** (**Slack**, **Microsoft Teams**, **Google Chat**, hoặc **Webhook (sự kiện JSON)** cho bộ
   nhận của riêng bạn), rồi dán **URL webhook**. Bạn cũng có thể nhập `secret:NAME` để đọc URL từ một
   [secret được quản lý](./secrets.md).
3. Bấm **Lưu**, rồi bấm **Thử** để gửi tin nhắn thử.

Bảng cho biết lần gần nhất mỗi kênh gửi thành công. **Gửi cảnh báo** tắt một kênh mà không xoá nó.

## Chọn endpoint nào gửi cảnh báo {#choose-which-endpoints-alert}

Cảnh báo mặc định tắt trên từng endpoint cho đến khi bạn bật **Cảnh báo khi lỗi** trong tab **Cài đặt** của endpoint và
publish. Từ đó lỗi máy chủ (5xx) được gửi tới mọi kênh, tối đa một lần cho mỗi endpoint và mã lỗi trong năm phút
(`opendbapi.alerts.dedup-window`). Lỗi phía client (4xx) không bao giờ gây cảnh báo.

Tin nhắn ghi tên workspace, endpoint, mã trạng thái, mã lỗi và request id; bạn có thể dán request id vào **Hoạt động**.
Các lần lặp bị chặn trong khoảng thời gian đó được đếm và báo trong tin nhắn kế tiếp.

## Quy tắc cho URL webhook {#rules-for-webhook-urls}

URL webhook phải dùng HTTPS; đặt `opendbapi.alerts.allow-http=true` để cho phép HTTP, chỉ dùng khi test. Redirect không
bao giờ được theo, và lần gửi nào không kết nối được trong 5 giây bị tính là thất bại.
