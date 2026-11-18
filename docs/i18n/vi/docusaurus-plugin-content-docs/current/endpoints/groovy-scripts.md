---
sidebar_position: 5
---

# Groovy script

Khi một query dễ dựng bằng code hơn là bằng template tag, hãy viết endpoint dưới dạng Groovy script. Chọn trong menu
**Ngôn ngữ** của tab **Truy vấn**:

- **Groovy script** (chế độ fragment): script dựng query rồi trả về; OpenDBAPI chạy nó với phân trang, row filter,
  masking, định dạng đầu ra và cache như thường lệ.
- **Script Groovy (full: db, trả về tuỳ ý)** (chế độ full): script tự chạy các query và trả về kết quả.

Chỉ thành viên có vai trò **script_author**, **admin** hoặc **owner** mới được viết, sửa hoặc preview script. Những
tác giả khác vẫn sửa được các phần còn lại của endpoint.

## Chế độ fragment {#fragment-mode}

Script trả về một fragment dựng bằng `sql"…"`. Mỗi `${…}` bên trong trở thành một tham số được bind, không bao giờ
thành văn bản SQL:

```groovy
def q = sql"select id, name, tier from customers where 1 = 1"
if (params.tier) q += sql" and tier = ${params.tier}"
if (params.ids) q += sql" and id in (${params.ids})"      // a list becomes ?, ?, ?
q += orderBy([newest: 'id desc', name: 'name'], params.sort, 'id')
return q
```

| Hàm | Tác dụng |
|---|---|
| `sql"…"` | Một fragment; `${value}` được bind, một list thành một placeholder cho mỗi phần tử (list rỗng bị từ chối) |
| `fragment + fragment` | Nối các fragment. Cộng một chuỗi thường vào fragment sẽ bị từ chối |
| `raw("…")` | Văn bản SQL viết trong script; chỉ nhận string literal |
| `orderBy(map, key, fallback)` | ` order by <value>`, trong đó giá trị lấy từ một map viết trong script, chọn theo key do người gọi gửi; key không có trong map thì dùng `fallback` |
| `params` | Map chỉ đọc chứa các tham số đã bind; bước kiểm tra nhận ra những tham số script đọc qua `params.name` hoặc `params['name']` |

Script phải trả về một fragment; trả về chuỗi thường là lỗi.

## Chế độ full {#full-mode}

Script chạy query qua `db` và trả về giá trị bất kỳ, giá trị này thành `data` trong response:

```groovy
def customer = db.one(sql"select id, name, tier from customers where id = ${params.id}")
if (!customer) return notFound("No customer ${params.id}")
def orders = db.list(sql"select id, status, total from orders where customer_id = ${customer.id} order by id desc limit 5")
return [customer: customer, recentOrders: orders, spent: db.value(sql"select sum(total) from orders where customer_id = ${customer.id}")]
```

| Hàm | Trả về |
|---|---|
| `db.one(q)` | Dòng đầu tiên dạng map, hoặc `null` |
| `db.list(q)` | Mọi dòng, tối đa **Số dòng trả về tối đa** |
| `db.value(q)` | Cột đầu tiên của dòng đầu tiên, hoặc `null` |
| `db.update(q)` | Số dòng mà một lệnh `INSERT`, `UPDATE` hoặc `DELETE` đã thay đổi |
| `notFound(message)` | Kết thúc request với `404 not_found` |
| `badRequest(message)` | Kết thúc request với `400 bad_request` |

Mọi query do script chạy đều qua cùng lớp bảo vệ như một query đã lưu, trước khi script nhận được các dòng:

- các kiểm tra tĩnh: chỉ một câu lệnh, `UPDATE` và `DELETE` phải có `WHERE`, lệnh ghi bị từ chối trên datasource chỉ
  đọc (`403 read_only_datasource`) và trong endpoint `GET` (`403 write_in_get`);
- đúng hàm cho từng loại câu lệnh: `db.update` chỉ dành cho lệnh ghi, các hàm còn lại chỉ dành cho query;
- row filter và mask của endpoint (xem [Chính sách dữ liệu](../access/data-policies.md));
- timeout và giới hạn số dòng của endpoint.

Mỗi lần chạy thực hiện tối đa 100 query trên một kết nối, trong một giao dịch khi bật **Giao dịch** (preview và test
luôn rollback), và dừng sau 30 giây (`opendbapi.script.full-timeout`). Endpoint chế độ full chỉ trả JSON: không có
phân trang hay streaming.

## Những gì script không làm được {#what-scripts-cannot-do}

Script chạy trong sandbox, được kiểm tra lúc biên dịch:

- không có import, annotation, khai báo class hay method, `new`, lời gọi static, `this` hay `super`;
- tên method và property phải viết rõ ra, không được tính toán; method gọi trên giá trị phải thuộc một allow list gồm
  các method thông dụng cho chuỗi, số, collection và ngày tháng (các helper về file và URL của Groovy không nằm trong
  danh sách này);
- chỉ một số ít kiểu dữ liệu (chuỗi, số, boolean, list, map, set, ngày tháng, `Math`);
- ở chế độ full, trên `db` chỉ có `one`, `list`, `value` và `update`;
- mỗi lần chạy bị giới hạn thời gian: 2 giây ở chế độ fragment (`opendbapi.script.timeout`).

:::warning
Sandbox giảm rủi ro nhưng không chứng minh được script vô hại. Chỉ cấp vai trò **script_author** cho người bạn tin
tưởng với thông tin đăng nhập của các datasource, và dùng một tài khoản cơ sở dữ liệu chỉ đọc được những gì endpoint
cần. Đặt `opendbapi.script.groovy-enabled=false` để tắt hoàn toàn script.
:::

Script bị lỗi sẽ trả `500 script_failed` cho người gọi, kèm request id; chi tiết được ghi vào nhật ký máy chủ và hiện
trong **Xem trước** cho tác giả.
