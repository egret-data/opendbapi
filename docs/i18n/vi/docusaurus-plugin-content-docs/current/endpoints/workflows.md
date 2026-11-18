---
sidebar_position: 6
---

# Workflow

Endpoint workflow trả về kết quả của nhiều bước: query, script, lời gọi tới endpoint khác trong workspace, và các
điểm rẽ nhánh chọn đường đi. Bạn vẽ workflow trên một canvas; các node không phụ thuộc nhau sẽ chạy cùng lúc.

![The Customer summary workflow on the canvas: a SQL node, a decision with Yes and No branches, a call to another endpoint and two end nodes](/img/endpoints/workflow-canvas.png)

## Tạo workflow {#create-a-workflow}

1. Trong tab **Truy vấn** của endpoint, chọn **Workflow (nhiều bước)** ở menu **Ngôn ngữ**. SQL hiện tại trở thành
   node đầu tiên, `main`.
2. Thêm node từ thanh công cụ phía trên canvas: **SQL**, **Groovy script**, **Gọi endpoint**, **Rẽ nhánh**,
   **Kết thúc**.
3. Kéo từ đáy một node sang node khác để node thứ hai chạy sau node thứ nhất. Với node rẽ nhánh, kéo từ **Có** hoặc
   **Không**.
4. Bấm vào một node để sửa nó trong panel cạnh canvas.
5. **Lưu**, chạy thử bằng **Xem trước**, rồi **Xuất bản**.

Chọn một đường nối và nhấn **Delete** để xoá. Tab **Danh sách** phía trên canvas sửa cùng các node đó dưới dạng danh
sách, tiện cho bàn phím và trình đọc màn hình.

Dòng phía trên canvas cho biết thứ tự chạy: `Runs in 4 step(s): customer → found → orders + missing → answer`. Các
node trong cùng một bước chạy song song.

## Các loại node {#node-kinds}

| Loại | Tác dụng | Kết quả |
|---|---|---|
| **SQL** | Một SQL template, như ở endpoint SQL | **Mọi dòng**, **Dòng đầu tiên** (hoặc `null`), **Một giá trị**, hoặc **Số dòng bị đổi** (với lệnh ghi) |
| **Groovy script** | Một [script chế độ full](./groovy-scripts.md#full-mode) có `db` | Giá trị script trả về |
| **Gọi endpoint** | Gọi một endpoint khác trong workspace | `data` của endpoint đó |
| **Rẽ nhánh** | Một điều kiện | `true` hoặc `false`, quyết định đi nhánh **Có** hay **Không** |
| **Kết thúc** | Một biểu thức, ví dụ `orders` | Giá trị của biểu thức |

Mỗi node có một **Id** (một chữ thường, theo sau là chữ, số và `_`) và một điều kiện **Chỉ khi** không bắt buộc.

## Truyền kết quả giữa các node {#passing-results-between-nodes}

Một node đọc kết quả của các node trước nó theo id, và đọc tham số của request theo tên:

- trong SQL: `#{customer.id}`, `#{id}`;
- trong script: `params.customer.id`, `params.id`;
- trong điều kiện và biểu thức: `customer.id`, `orders[0].total`, `count[0].num == 0`.

## Nhánh và điều kiện {#branches-and-conditions}

- Node nằm sau **Có** của một node rẽ nhánh chỉ chạy khi điều kiện đúng; sau **Không** thì chỉ chạy khi điều kiện
  sai. Node trên nhánh không được chọn sẽ không chạy, các node sau nó cũng vậy.
- **Chỉ khi** bỏ qua một node khi điều kiện của nó không thoả; kết quả của node là `null` và các node sau nó vẫn chạy.
- Node **Kết thúc** đóng một nhánh; không gì đứng sau nó được.

## Kết quả trả về {#the-answer}

Menu **Kết quả trả về** phía trên canvas chọn node có kết quả làm `data` của response. **Node cuối cùng đã chạy**
(mặc định) là node cuối cùng theo thứ tự danh sách đã chạy và không có node nào phụ thuộc vào nó. Thường đó là node
kết thúc của nhánh được chọn.

## Gọi endpoint khác {#calling-other-endpoints}

Node **Gọi endpoint** chọn một endpoint trong workspace và gán cho mỗi tham số của nó một biểu thức: tên một tham số
(`name`), một trường của node trước (`customer.id`), hoặc một hằng (`'gold'`, `10`). Endpoint được gọi:

- chạy phiên bản **đang chạy** của nó, với kiểm tra tham số, quy tắc, datasource và chính sách dữ liệu riêng, dưới
  danh nghĩa cùng người gọi;
- chạy trong giao dịch của workflow khi cả hai dùng chung datasource và workflow chạy trong một giao dịch;
- không được thay đổi dữ liệu khi workflow trả lời `GET`.

Các endpoint gọi lẫn nhau sẽ dừng ở cấp thứ ba với `500 workflow_failed`. Một lời gọi thất bại làm workflow thất bại.

## Giao dịch và các bước song song {#transactions-and-parallel-steps}

Khi bật **Giao dịch** (trong **Cài đặt** của endpoint), mọi node chạy tuần tự trên một kết nối và cùng commit hoặc cùng
rollback. Khi tắt, các node trong cùng một bước chạy song song, mỗi node trên kết nối riêng. Preview và test luôn chạy
trong một giao dịch rồi rollback.

## Giới hạn {#limits}

- Tối đa 20 node và 100 query cho mỗi request.
- Workflow chỉ trả JSON: không có phân trang hay streaming.
- Node **Groovy script** cần vai trò **script_author**.
- Bước kiểm tra xem xét đồ thị (id không trùng, thiếu phụ thuộc, vòng lặp), render từng node SQL với giá trị mẫu, và
  cảnh báo khi một nhánh rẽ không có node nào.
