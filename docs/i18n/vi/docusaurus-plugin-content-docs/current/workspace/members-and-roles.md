---
sidebar_position: 1
---

# Thành viên và vai trò

Người viết endpoint tham gia workspace dưới dạng **thành viên**, mỗi người có một vai trò. Chương trình gọi endpoint là
[service account](../access/clients.md) và không phải thành viên.

![The Members tab of workspace settings](/img/workspace/members.png)

## Vai trò {#roles}

Các vai trò xếp từ thấp đến cao; vai trò sau có mọi quyền của các vai trò trước nó.

| Vai trò | Được làm gì |
|---|---|
| **Người xem** | Xem mọi thứ trong workspace, chỉ đọc |
| **Tác giả** | Viết, preview và publish endpoint SQL và workflow; chạy test |
| **Tác giả script** | Quyền của Tác giả, thêm endpoint [Groovy script](../endpoints/groovy-scripts.md) và node script |
| **Quản trị viên** | Thành viên, datasource, client, secret, cảnh báo và bảo mật |
| **Chủ sở hữu** | Mọi thứ, kể cả các chủ sở hữu khác và tên workspace |

Workspace luôn phải có ít nhất một chủ sở hữu. Chỉ chủ sở hữu mới thay đổi được chủ sở hữu khác.

**Quản trị viên hệ thống** (đặt tại **Quản trị → Người dùng → Đặt làm quản trị viên hệ thống**) tạo và lưu trữ workspace,
quản lý người dùng và identity provider, và xem audit log toàn máy chủ. Là quản trị viên hệ thống không có nghĩa bạn là
thành viên của mọi workspace.

## Thêm thành viên {#add-a-member}

1. Mở **Cài đặt → Thành viên** và bấm **Thêm thành viên**.
2. Tìm theo **Tên, tên đăng nhập hoặc email** và chọn người dùng. Người dùng LDAP và SSO chỉ xuất hiện sau lần đăng nhập
   đầu tiên.
3. Chọn **Vai trò** và bấm **Thêm thành viên**.

Dùng **Đổi** cạnh một thành viên để đổi vai trò, và **Gỡ** để thu hồi quyền truy cập của họ ngay lập tức.

## Vai trò từ nhóm trong thư mục {#roles-from-directory-groups}

Khi một [identity provider](../deploy/authentication.md) ánh xạ nhóm trong thư mục sang vai trò workspace, thành viên nhận
vai trò lúc đăng nhập. Những thành viên này hiện nhãn **Từ nhóm**, và vai trò của họ chỉ thay đổi được qua nhóm hoặc qua
ánh xạ.
