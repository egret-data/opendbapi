---
sidebar_position: 7
---

# Sắp xếp endpoint theo nhóm

Nhóm là thư mục cho danh sách **Endpoint**. Nhóm chỉ có một cấp, dùng chung cho mọi người trong workspace và không
bao giờ xuất hiện trong URL. Nhóm cũng mang quyền truy cập: một service account [được cấp quyền trên một
nhóm](../access/clients.md#grant-access) có thể gọi mọi endpoint trong nhóm đó.

Viewer thấy các nhóm; tác giả tạo, đổi tên, xoá nhóm và chuyển endpoint giữa các nhóm.

![The Endpoints list grouped into Customers and Orders, then No group](/img/endpoints/groups.png)

## Danh sách hiển thị thế nào {#how-the-list-looks}

Khi workspace có ít nhất một nhóm, danh sách được gom dưới các tiêu đề kèm số endpoint trong mỗi nhóm, ví dụ
**Reports (3)**, rồi đến **Không có nhóm** cho các endpoint không thuộc nhóm nào. Bấm vào tiêu đề để thu gọn hoặc mở
rộng; trình duyệt sẽ nhớ trạng thái này. Khi tìm kiếm hoặc lọc, chỉ những nhóm có endpoint khớp được hiển thị. Dòng
tóm tắt cũng đếm cả số nhóm.

Chế độ xem **Thùng rác** không gom nhóm.

## Tạo nhóm {#create-a-group}

Bấm **Nhóm mới** trên thanh công cụ, nhập **Tên** rồi bấm **Tạo**. Tên nhóm là duy nhất trong workspace. Bạn cũng có
thể tạo nhóm trong lúc chuyển endpoint, xem bên dưới.

## Chuyển endpoint {#move-endpoints}

Có ba cách chuyển một endpoint:

- Mở menu **…** của endpoint và chọn **Move to** một nhóm, **Đưa ra khỏi nhóm**, hoặc **Move to new group…**. Lựa
  chọn cuối hỏi tên, tạo nhóm và chuyển endpoint vào trong một bước.
- Kéo một dòng thả vào tiêu đề nhóm, hoặc vào **Không có nhóm**.
- Bấm **Chọn** trên thanh công cụ để hiện ô đánh dấu, tick nhiều endpoint, bấm **Move n to group…** rồi chọn một
  nhóm, **Không có nhóm** hoặc **Nhóm mới…**. Khi đang bật **Chọn**, kéo một dòng đã tick sẽ chuyển mọi dòng đã tick.

Việc chuyển nhóm không thay đổi những gì endpoint phục vụ và không cần publish: bản nháp, phiên bản đang chạy và URL
giữ nguyên. Nhưng nó thay đổi ai được gọi endpoint. Khi endpoint được chuyển ra khỏi hoặc vào một nhóm đã cấp quyền cho
service account, OpenDBAPI sẽ hỏi trước, ví dụ *Service accounts granted Reports lose access (2).* Thay đổi có hiệu lực
trên mọi instance máy chủ cùng lúc.

Tab **Cài đặt** của endpoint cũng có trường **Nhóm**; lưu bản nháp sẽ chuyển endpoint theo cách tương tự.

## Đổi tên hoặc xoá nhóm {#rename-or-delete-a-group}

Menu **…** trên tiêu đề nhóm có:

| Mục | Tác dụng |
|---|---|
| **Đổi tên** | Đổi tên nhóm. Các grant trên nhóm được giữ nguyên |
| **Xoá nhóm…** | Xoá nhóm. Endpoint trong nhóm không bị xoá mà chuyển sang **Không có nhóm**. Các grant trên nhóm bị thu hồi, và hộp thoại cho biết bao nhiêu service account mất quyền truy cập |

## Trong file cấu hình {#in-configuration-files}

Trong [YAML](../config/yaml-format.md), một document `Group` khai báo một nhóm, còn `metadata.group` của endpoint ghi
tên nhóm chứa nó; endpoint ghi tên một nhóm chưa khai báo hoặc không tồn tại sẽ bị từ chối. Trong workspace được quản
lý bằng file cấu hình, nhóm và việc chuyển nhóm chỉ thay đổi qua `opendbapi apply`.

## Qua API {#through-the-api}

| Request | Tác dụng |
|---|---|
| `GET /admin/api/ws/{ws}/groups` | Danh sách nhóm kèm `endpointCount` và `grantCount` |
| `POST /admin/api/ws/{ws}/groups` | Tạo: `{"name": "Reports"}` |
| `PATCH /admin/api/ws/{ws}/groups/{id}` | Đổi tên |
| `DELETE /admin/api/ws/{ws}/groups/{id}` | Xoá; endpoint thành không có nhóm, các grant trên nhóm bị thu hồi |
| `PUT /admin/api/ws/{ws}/endpoints/{id}/group` | Chuyển: `{"groupId": "…"}`, hoặc `{"groupId": null}` để bỏ nhóm |

Thay đổi nhóm và việc chuyển nhóm được ghi vào nhật ký audit dưới dạng `group.create`, `group.rename`, `group.delete`
và `endpoint.move`. Xem [Management API](../reference/admin-api.md#groups) để biết chi tiết.
