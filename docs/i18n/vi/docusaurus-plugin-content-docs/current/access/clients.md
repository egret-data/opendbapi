---
sidebar_position: 1
---

# Client và grant

Chương trình gọi endpoint private bằng một OAuth2 token. **Service account** là danh tính của chương trình, **client**
giữ id và secret mà chương trình đổi lấy token, còn **grant** cho phép service account gọi một endpoint hoặc một nhóm.

![The Clients page with the reporting service account, its active clients and grants](/img/access/clients.png)

Admin của workspace quản lý service account; author chỉ xem được.

## Tạo service account {#create-a-service-account}

1. Mở **Client** và bấm **Service account mới**.
2. Đặt tên theo hệ thống sẽ gọi API (`reporting`, `partner-acme`), thêm **Mô tả (không bắt buộc)**, rồi bấm **Tạo**.

Mỗi hệ thống nên có một service account riêng. Khi đó bạn thu hồi được một hệ thống mà không ảnh hưởng hệ thống khác,
và xem được lời gọi của từng hệ thống trong **Hoạt động**.

## Cấp quyền truy cập {#grant-access}

Trên trang của service account, trong mục **Quyền đã cấp**, bấm **Cấp quyền**, chọn **Loại** và **Tài nguyên**, rồi bấm
**Cấp quyền**:

- **Endpoint**: một endpoint.
- **Nhóm (mọi endpoint của nhóm)**: mọi endpoint trong nhóm, kể cả endpoint thêm vào sau này. Chuyển một endpoint ra khỏi
  nhóm, hoặc xoá nhóm, sẽ lấy lại quyền; xem [Nhóm](../endpoints/groups.md).

Nếu chưa có grant, lời gọi nhận `403 not_granted`. Tab **Truy cập** của endpoint liệt kê mọi service account được gọi
endpoint đó, trực tiếp hoặc **Qua** nhóm của nó, và cũng cấp quyền được ngay tại đó.

![The service account page: description, clients, grants and attributes](/img/access/service-account.png)

## Tạo client {#create-a-client}

1. Trong mục **Client**, bấm **Client mới**.
2. Nếu cần, đặt **Hết hạn sau (số ngày, không bắt buộc)**.
3. Bấm **Tạo client** rồi sao chép **Client id** (`odc_…`) và **Client secret**. Secret chỉ hiện một lần và chỉ được lưu
   dưới dạng hash; bấm **Tôi đã lưu secret** khi bạn đã cất nó.

| Thao tác | Tác dụng |
|---|---|
| **Đổi secret** | Cấp secret mới; secret hiện tại ngừng hoạt động ngay. Cập nhật các hệ thống đang dùng nó |
| **Thu hồi** | Client ngừng hoạt động ngay; các token của nó mất quyền trong vài giây. Không hoàn tác được |
| **Bảo mật** | Bắt buộc request có chữ ký hoặc mã hoá phản hồi; xem [Ký và mã hoá request](./request-security.md) |

Bảng cho biết lần gần nhất mỗi client lấy token.

## Thuộc tính {#attributes}

**Thuộc tính** là thông tin về người gọi mà [row filter](./data-policies.md#row-filters) đọc, ví dụ `country = VN` hoặc
`regions = north,south`. Dùng đúng kiểu của cột được lọc: cột số thì nhập số. Các giá trị của một danh sách cách nhau bằng
dấu phẩy.

## Tắt hoặc xoá {#disable-or-delete}

- **Tắt** dừng mọi client của service account trong vài giây; **Bật** cho chúng hoạt động lại.
- **Xoá…** xoá service account cùng các client và grant của nó. Không hoàn tác được.

## Đọc thêm {#further-reading}

- [Gọi endpoint](./calling-endpoints.md): lấy token và gọi một endpoint.
- [Chính sách dữ liệu](./data-policies.md): masking và row filter theo từng người gọi.
