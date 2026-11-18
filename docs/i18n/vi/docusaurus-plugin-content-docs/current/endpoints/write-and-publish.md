---
sidebar_position: 1
---

# Viết và publish endpoint

Một endpoint gồm một query cùng URL, tham số và cấu hình của nó. Bạn sửa **bản nháp**, chạy thử bằng **Xem trước**, rồi
**Xuất bản** nó thành một phiên bản có đánh số. Người gọi nhận phiên bản đó.

![Tab Truy vấn của một endpoint: datasource, ngôn ngữ, trình soạn SQL, tham số và Xem trước](/img/endpoints/query.png)

## Tạo endpoint {#create-an-endpoint}

1. Mở **Endpoint** và bấm **Endpoint mới**.
2. Điền form và bấm **Tạo và sửa truy vấn**:

| Trường | Ý nghĩa |
|---|---|
| **Tên** | Tên hiển thị trong danh sách |
| **Phương thức** | `GET` để đọc; `POST`, `PUT` và `DELETE` có thể thay đổi dữ liệu |
| **Đường dẫn** | Các đoạn gồm chữ cái, chữ số, `_` và `-`, phân tách bằng `/`; `{name}` là tham số path, ví dụ `v1/customers/{id}`. Không đổi được khi đã chạy |
| **Nhóm** | Một thư mục tuỳ chọn, không thuộc URL. Grant có thể áp cho cả nhóm. Xem [Nhóm](./groups.md) |
| **Nguồn dữ liệu** | Database mà query chạy trên đó |
| **Truy cập** | **Riêng tư** cần token của client; **Công khai** trả lời bất kỳ ai truy cập được server |

Sau khi publish, endpoint trả lời tại `/api/{workspace}/{path}`.

## Viết query {#write-the-query}

Tab **Truy vấn** chứa trình soạn SQL. Chọn **Nguồn dữ liệu** và **Ngôn ngữ**:

| Ngôn ngữ | Dùng khi |
|---|---|
| **SQL template** | Một câu SQL có tham số và các phần có điều kiện (mặc định) |
| **Groovy script** | Dựng query bằng code; xem [Groovy script](./groovy-scripts.md) |
| **Script Groovy (full: db, trả về tuỳ ý)** | Chạy nhiều query và tự định hình kết quả trả về |
| **Workflow (nhiều bước)** | Một đồ thị gồm query, script và lời gọi tới endpoint khác; xem [Workflow](./workflows.md) |

### Tham số trong SQL {#parameters-in-sql}

Viết tham số dạng `#{name}`. OpenDBAPI gửi nó tới database dưới dạng **giá trị bind**. Nội dung request không bao giờ được
chèn thẳng vào SQL, nên người gọi không thể SQL injection. Vì lý do đó `${...}` bị từ chối.

```sql
select id, name, tier from customers where id = #{id}
```

Giá trị lồng nhau dùng dấu chấm: `#{user.id}`.

### SQL có điều kiện {#conditional-sql}

SQL template dùng các thẻ kiểu MyBatis:

| Thẻ | Tác dụng |
|---|---|
| `<if test="…">` | Đưa nội dung vào khi biểu thức đúng, ví dụ `test="tier != null"` |
| `<where>` | Thêm `WHERE` khi bên trong có nội dung và bỏ `AND`/`OR` ở đầu |
| `<set>` | Thêm `SET` cho câu update và bỏ dấu phẩy ở cuối |
| `<trim prefix="…" suffix="…" prefixOverrides="…" suffixOverrides="…">` | Dạng tổng quát của `<where>` và `<set>` |
| `<choose>` / `<when test="…">` / `<otherwise>` | Nhánh khớp đầu tiên |
| `<foreach collection="ids" item="id" open="(" close=")" separator=",">` | Lặp nội dung cho từng phần tử; mỗi `#{id}` đều được bind |
| `<bind name="pattern" value="'%' + q + '%'"/>` | Đặt tên cho một giá trị tính toán |
| `<orderBy param="sort" allow="id, name, total" default="id"/>` | Người gọi chọn cách sắp xếp, chỉ trong các cột được phép |

```sql
select id, name, country, tier from customers
<where>
  <if test="tier != null">tier = #{tier}</if>
  <if test="ids != null and ids.size() > 0">and id in
    <foreach collection="ids" item="id" open="(" close=")" separator=", ">#{id}</foreach>
  </if>
</where>
<orderBy param="sort" allow="id, name" default="id"/>
```

### Tham số {#parameters}

Bảng **Tham số** khai báo những gì người gọi được truyền vào. **Phát hiện từ SQL** thêm mọi tên mà SQL đọc nhưng chưa được
khai báo.

| Cột | Ý nghĩa |
|---|---|
| **Tên** | Tên dùng trong SQL |
| **Vị trí** | `query` (`?tier=gold`), `path` (`{id}` trong path), `header`, hoặc `body` (một trường JSON) |
| **Loại** | `string`, `bigint`, `double`, `decimal`, `boolean`, `date`, `datetime`, hoặc mảng: `array<string>`, `array<bigint>`, `array<double>`, `array<date>` |
| **Bắt buộc** | Thiếu giá trị bắt buộc sẽ trả về `400 invalid_param` |
| **Mặc định** | Dùng khi người gọi không truyền gì |

Mỗi tham số còn có thể có mô tả (hiện trong tài liệu API được sinh ra), danh sách giá trị cho phép, giá trị nhỏ nhất và
lớn nhất, một pattern, và số phần tử tối đa cho mảng. Giá trị không chuyển được sang đúng kiểu sẽ trả về
`400 invalid_param` kèm tên tham số.

## Xem trước {#preview}

Bên dưới trình soạn, **Xem trước** chạy query đang hiển thị với các giá trị bạn nhập. Nó không lưu gì và chạy trong một
transaction luôn được rollback. Kết quả gồm các dòng, câu SQL đã chạy cùng giá trị bind, cảnh báo từ các bước kiểm tra,
và schema đầu ra.

![Xem trước endpoint Recent orders: các dòng trả về với status = paid](/img/endpoints/preview.png)

## Lưu và publish {#save-and-publish}

- **Lưu** lưu bản nháp. Người gọi vẫn nhận phiên bản đang chạy.
- **Xuất bản** kiểm tra bản nháp đã lưu (SQL được parse, endpoint `GET` không được thay đổi dữ liệu, datasource chỉ đọc
  từ chối lệnh ghi, `UPDATE` và `DELETE` phải có `WHERE`) rồi cố định nó thành phiên bản kế tiếp, được phục vụ ngay.
  **Ghi chú (tuỳ chọn)** được lưu kèm phiên bản.

Phần đầu trang hiện `Live · v3`, và hiện **Thay đổi chưa xuất bản** khi bản nháp khác phiên bản đang chạy.

:::note
Không đổi được phương thức và path khi endpoint đang chạy, vì người gọi phụ thuộc vào chúng. Hãy tắt endpoint trước, hoặc
tạo [phiên bản major mới](./versions-and-majors.md) ở path mới.
:::

## Phiên bản và rollback {#versions-and-rollback}

Tab **Phiên bản** liệt kê mọi phiên bản đã publish cùng ghi chú, người publish và thời điểm. Bạn có thể so sánh hai phiên
bản bất kỳ, hoặc một phiên bản với bản nháp đã lưu, và rollback: phiên bản được chọn lại được phục vụ, còn bản nháp giữ
nguyên.

## Tắt, xoá vào thùng rác và khôi phục {#disable-trash-and-restore}

Từ các thao tác cạnh nút **Xuất bản**:

- **Tắt** ngừng phục vụ (người gọi nhận `404`) nhưng giữ phiên bản đang chạy và các grant; **Bật** đưa nó trở lại.
- **Chuyển vào thùng rác** (chỉ cho endpoint là bản nháp hoặc đã tắt) ẩn endpoint. **Khôi phục** đưa nó về trạng thái đã
  tắt, hoặc về bản nháp nếu nó chưa từng được publish. Endpoint không bao giờ được khôi phục thẳng về trạng thái đang chạy.
- **Nhân bản** sao chép bản nháp sang một endpoint mới tại `<path>-copy`.

## Đọc thêm {#further-reading}

- [Cài đặt endpoint](./settings.md): phân trang, đầu ra, cache, giới hạn và các mục khác.
- [Kiểm thử](./tests.md): các bước kiểm tra chạy trước khi publish.
- [Gọi endpoint](../access/calling-endpoints.md): người gọi gửi gì và nhận gì.
