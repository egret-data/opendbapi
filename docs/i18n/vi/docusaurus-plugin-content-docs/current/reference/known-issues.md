---
sidebar_position: 4
---

# Các vấn đề đã biết

Các lỗi trong bản phát hành hiện tại (0.3.0) và cách xử lý tạm thời.

## Giá trị `timestamptz` bị mất offset {#timestamptz-values-lose-their-offset}

Cột `timestamp with time zone` của PostgreSQL được trả về dưới dạng chuỗi ngày giờ cục bộ không kèm offset, ví dụ
`"2026-10-06T00:00"`. Client không biết giá trị đó thuộc múi giờ nào.

Để xử lý tạm thời, chuyển đổi giá trị trong SQL, ví dụ
`to_char(placed_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')`, hoặc đặt
[kiểu giá trị](../endpoints/settings.md#output) của cột là **Epoch mili giây**.

## Nút **Xuất bản** vẫn bật khi không có gì để publish {#publish-is-enabled-when-there-is-nothing-to-publish}

Khi bản nháp đã lưu giống hệt phiên bản đang chạy, nút **Xuất bản** vẫn bật, và nhấn vào sẽ báo lỗi
`409 nothing_to_publish`.

Không cần xử lý gì. Phiên bản đang chạy vẫn giữ nguyên.
