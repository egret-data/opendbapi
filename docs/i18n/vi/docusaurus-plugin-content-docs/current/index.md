---
slug: /
sidebar_position: 0
title: Giới thiệu
---

# OpenDBAPI

OpenDBAPI biến SQL thành REST API. Bạn viết một query, khai báo các tham số mà người gọi được phép truyền, rồi publish.
Sau đó, bất kỳ chương trình nào có OAuth2 token đều gọi được nó qua HTTP và nhận về JSON. Bạn không phải viết hay triển
khai một service nào, cũng không phải phát thông tin đăng nhập database cho ai.

![The endpoints list of the demo workspace: published endpoints with their method, path and state](/img/get-started/endpoints-list.png)

Dùng OpenDBAPI khi một chương trình cần kết quả của một query do bạn kiểm soát: một job chạy hằng đêm, backend của đối
tác, một ứng dụng di động hay một macro trong bảng tính.

## Bạn có những gì {#what-you-get}

- Endpoint viết bằng SQL template, có tham số định kiểu, kiểm tra hợp lệ, phân trang, định dạng kết quả và cache. Một
  endpoint cũng có thể là một Groovy script hoặc một workflow nhiều bước.
- Publish theo phiên bản. Bạn sửa bản nháp và publish các phiên bản đánh số, có thể quay lui về phiên bản cũ. Các test
  đã lưu chạy trước khi publish, và các phiên bản major có thể chạy song song kèm header báo ngừng hỗ trợ (deprecation).
- Kiểm soát truy cập. Các workspace tách biệt nhau, người viết endpoint có vai trò riêng, còn chương trình gọi endpoint
  dùng OAuth2 client credentials. Ký request và mã hóa response là tùy chọn.
- Chính sách dữ liệu: che cột và lọc dòng theo người gọi.
- Vận hành: màn hình hoạt động ghi lại mọi lời gọi, cảnh báo gửi tới Slack hoặc webhook, nhật ký kiểm toán, metric
  Prometheus và tracing.
- Cấu hình dưới dạng code. Xuất workspace ra YAML, lưu trong Git, rồi `plan` / `apply` sang môi trường khác bằng dòng
  lệnh `opendbapi`.

## Bắt đầu từ đâu {#where-to-start}

- [Bắt đầu nhanh](./get-started/quick-start.md): chạy OpenDBAPI và một database mẫu bằng Docker Compose.
- [Khái niệm](./get-started/concepts.md): workspace, endpoint, phiên bản, client và grant trong một trang.
- [Endpoint đầu tiên](./get-started/first-endpoint.md): từ một query SQL tới một URL gọi được.
- [Triển khai bằng Docker](./deploy/docker.md) hoặc [trên Kubernetes](./deploy/kubernetes.md) khi bạn sẵn sàng chạy thật.

OpenDBAPI là mã nguồn mở (Apache-2.0) tại [github.com/egret-data/opendbapi](https://github.com/egret-data/opendbapi).
