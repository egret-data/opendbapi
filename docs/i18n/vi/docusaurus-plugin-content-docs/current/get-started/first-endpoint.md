---
sidebar_position: 3
---

# Endpoint đầu tiên

Biến một query SQL trên cửa hàng mẫu thành một URL gọi được. Bạn cần [bắt đầu nhanh](./quick-start.md) đang chạy, dữ
liệu mẫu đã nạp, và đã đăng nhập bằng `admin`.

## 1. Tạo workspace {#1-create-a-workspace}

1. Trên thanh bên, trong mục **Quản trị**, mở **Workspace** và nhấn **Workspace mới**.
2. Nhập **Tên** (`Shop`) và **Slug** (`shop`). Slug nằm trong URL của mọi endpoint và không đổi được về sau.
3. Giữ bạn là **Chủ sở hữu đầu tiên** và nhấn **Tạo workspace**.

Bộ chuyển workspace ở trên cùng giờ hiển thị **Shop**.

## 2. Kết nối database {#2-connect-the-database}

1. Mở **Nguồn dữ liệu** và nhấn **Nguồn dữ liệu mới**.
2. Điền nhóm **Kết nối**:

   | Thiết lập | Giá trị |
   |---|---|
   | **Tên** | `shop` |
   | **Loại cơ sở dữ liệu** | PostgreSQL |
   | **Host** | `postgres` (tên container database trên mạng Compose) |
   | **Cổng** | để trống để dùng 5432 |
   | **Cơ sở dữ liệu** | `opendbapi` |

3. Trong **Thông tin đăng nhập**, đặt **Tên đăng nhập** là `shop_reader` và **Mật khẩu** là `shop-reader`.
4. Trong **Thuộc tính driver**, nhấn **Thêm thuộc tính** và đặt `currentSchema` thành `shop`, để query viết được
   `customers` thay vì `shop.customers`.
5. Giữ **Chỉ đọc** ở trạng thái bật và nhấn **Kiểm tra kết nối**. Khi thấy **Đã kết nối.**, nhấn **Tạo nguồn dữ liệu**.

![The shop datasource with its connection settings and a Connected badge](/img/datasources/detail.png)

## 3. Viết endpoint {#3-write-the-endpoint}

1. Mở **Endpoint** và nhấn **Endpoint mới**.
2. Đặt tên là `Customers`, giữ **Phương thức** `GET`, đặt **Đường dẫn** là `customers`, chọn datasource `shop` và giữ
   **Truy cập** ở **Riêng tư**. Nhấn **Tạo và sửa truy vấn**.
3. Thay query bằng:

   ```sql
   select id, name, country, tier from customers
   <where><if test="tier != null">tier = #{tier}</if></where>
   order by id
   ```

   `#{tier}` là một tham số, được gửi tới database dưới dạng giá trị bind, không bao giờ được chèn thẳng vào SQL. Thẻ
   `<where>` và `<if>` chỉ thêm điều kiện khi người gọi truyền `tier`.
4. Nhấn **Phát hiện từ SQL** cạnh **Tham số**: một tham số `tier` kiểu `string` xuất hiện.
5. Trong **Xem trước**, gõ `gold` cho `tier` và nhấn **Chạy**. Hai khách hàng hạng gold hiện ra, kèm câu SQL đã chạy và
   các giá trị bind của nó.

![The query editor of the Customers endpoint with the tier parameter and the Preview panel](/img/endpoints/query.png)

## 4. Publish {#4-publish}

Nhấn **Lưu**, rồi **Xuất bản** và **Xuất bản phiên bản mới**. Header đổi thành `Live · v1`.

## 5. Gọi endpoint {#5-call-it}

Endpoint riêng tư cần token. Tạo token cho một service account:

1. Mở **Client**, nhấn **Service account mới**, đặt tên `tutorial` và nhấn **Tạo**.
2. Trong **Quyền đã cấp**, nhấn **Cấp quyền**, chọn endpoint `Customers` và nhấn **Cấp quyền**.
3. Trong **Client**, nhấn **Client mới** rồi **Tạo client**. Sao chép **Client id** và **Client secret**. Secret chỉ
   hiển thị một lần.

Đổi client lấy token, rồi gọi endpoint:

```bash
TOKEN=$(curl -s -u "$CLIENT_ID:$CLIENT_SECRET" -d grant_type=client_credentials \
  http://localhost:8520/oauth2/token | jq -r .access_token)

curl -s -H "Authorization: Bearer $TOKEN" "http://localhost:8520/api/shop/customers?tier=gold"
```

```json
{
  "data": [
    {"id": 1, "name": "Ada Lovelace", "country": "GB", "tier": "gold"},
    {"id": 2, "name": "Grace Hopper", "country": "US", "tier": "gold"}
  ],
  "meta": {"version": 1, "cached": false, "requestId": "cfbf7475-…"}
}
```

`meta.version` cho biết phiên bản đã publish nào đã trả lời. Mọi lời gọi đều hiện trong **Hoạt động**.

## Tiếp theo {#next}

- [Viết và publish endpoint](../endpoints/write-and-publish.md): chi tiết về trình soạn thảo, tham số và phiên bản.
- [Gọi endpoint](../access/calling-endpoints.md): token, phong bì response và lỗi.
- [Cấu hình dưới dạng code](../config/yaml-format.md): lưu workspace này trong Git.
