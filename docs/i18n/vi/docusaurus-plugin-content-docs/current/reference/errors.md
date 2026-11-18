---
sidebar_position: 2
---

# Lỗi

Endpoint đã publish trả lỗi theo định dạng problem của RFC 9457 (`Content-Type: application/problem+json`), kèm một
`code` ổn định và `requestId` của lời gọi:

```json
{
  "type": "about:blank",
  "title": "Bad Request",
  "status": 400,
  "detail": "Parameter 'tier' must be one of gold, silver, bronze.",
  "code": "invalid_param",
  "requestId": "4f1c9f0e-3a57-4d07-9a8e-2a1f1b0b6c11"
}
```

Hãy lập trình dựa trên `status` và `code`. Nội dung `detail` dành cho người đọc và có thể thay đổi. Với lỗi server
(5xx), chi tiết nằm trong nhật ký server và trong **Hoạt động**, tìm theo `requestId`.

## Gọi endpoint {#calling-endpoints}

| Status | Code | Ý nghĩa |
|---|---|---|
| 400 | `invalid_param` | Một tham số bị thiếu, sai kiểu, hoặc vi phạm ràng buộc của nó |
| 400 | `rule_failed` | Một [quy tắc tham số](../endpoints/settings.md#parameter-rules) không thỏa; `detail` là thông báo của quy tắc đó |
| 400 | `invalid_cursor` | Cursor `after` không phải do endpoint này cấp |
| 400 | `invalid_body` | Không đọc được JSON body |
| 400 | `bad_request` | Một script ở chế độ full đã gọi `badRequest(…)` |
| 401 | `unauthenticated` | Endpoint riêng tư được gọi mà không có token (`WWW-Authenticate: Bearer realm="opendbapi"`) |
| 401 | `invalid_token` | Token sai định dạng, hết hạn, hoặc client của nó đã bị thu hồi hay vô hiệu hóa |
| 401 | `bad_signature` | Client phải [ký request](../access/request-security.md) nhưng chữ ký bị thiếu, quá hạn, sai hoặc bị dùng lại |
| 403 | `not_granted` | Service account không có grant trên endpoint này hoặc nhóm của nó |
| 403 | `ip_blocked` | [Firewall](../workspace/security.md#firewall) của workspace từ chối địa chỉ của người gọi |
| 403 | `missing_attribute` | Một row filter cần thuộc tính mà service account không có |
| 403 | `row_filter_needs_client` | Có row filter áp dụng, nhưng lời gọi không có client |
| 403 | `write_in_get` | Một endpoint `GET` cố sửa dữ liệu |
| 400 | `transaction_unsupported` | Endpoint chạy trong transaction, nhưng datasource của nó (StarRocks, Apache Doris, ClickHouse) [không hỗ trợ transaction](../datasources/datasources.md#databases-without-transactions) |
| 403 | `read_only_datasource` | Một query cố sửa dữ liệu trên datasource chỉ đọc |
| 404 | `not_found` | Không có endpoint đã publish nào ở phương thức và đường dẫn này, hoặc endpoint **Một đối tượng** không tìm thấy dòng nào |
| 406 | `not_acceptable` | Định dạng được yêu cầu không được phép (streaming đang tắt, hoặc một client có mã hóa yêu cầu stream) |
| 413 | `body_too_large` | Body lớn hơn 1 MB |
| 415 | `unsupported_media_type` | Body không phải JSON |
| 429 | `rate_limited` | Vượt rate limit; chờ `Retry-After` giây |
| 500 | `query_failed` | Database từ chối query |
| 502 | `upstream_failed` | Một [HTTP API](../datasources/http-apis.md#how-calls-end) trả về lỗi, redirect hoặc dữ liệu không phải JSON, hoặc không truy cập được |
| 500 | `script_failed` | Một Groovy script thất bại |
| 500 | `workflow_failed` | Một node của workflow thất bại, hoặc các endpoint gọi lẫn nhau quá sâu |
| 500 | `template_failed` | Không render được SQL template với các giá trị này |
| 500 | `row_filter_failed` / `keyset_failed` | Không dựng được query đã lọc hoặc đã phân trang |
| 503 | `datasource_missing` | Datasource của endpoint đã bị xóa |
| 503 | `endpoint_unavailable` | Endpoint tạm thời không trả lời được |
| 504 | `query_timeout` | Query chạy lâu hơn timeout của endpoint |

## Token endpoint {#token-endpoint}

`POST /oauth2/token` trả lỗi theo định dạng OAuth2, `{"error": "…", "error_description": "…"}`:

| Status | `error` | Ý nghĩa |
|---|---|---|
| 400 | `invalid_request` | Request thiếu `grant_type` hoặc thông tin xác thực của client |
| 400 | `unsupported_grant_type` | Chỉ hỗ trợ `client_credentials` |
| 401 | `invalid_client` | Client không tồn tại, sai secret, hoặc client đã bị thu hồi, hết hạn hay vô hiệu hóa |

## Management API {#management-api}

Management API (`/admin/api`) dùng cùng định dạng problem. Các mã bạn có thể gặp khi tự động hóa nó:

| Status | Code | Ý nghĩa |
|---|---|---|
| 400 | `invalid_endpoint`, `invalid_datasource`, `invalid_policy`, … | Request vi phạm một quy tắc; `detail` cho biết quy tắc nào |
| 400 | `not_a_database` | Yêu cầu lấy bảng, schema và cột từ một datasource HTTP API |
| 400 | `preview_cannot_roll_back` | Preview từ chối một câu lệnh sửa dữ liệu trên database [không có transaction](../datasources/datasources.md#databases-without-transactions) |
| 409 | `validation_failed` | Khi publish, bản nháp có lỗi (SQL sai, ghi dữ liệu trong `GET`, request HTTP trên database, …) |
| 409 | `config_locked` | Workspace [được quản lý bằng file cấu hình](../config/plan-and-apply.md#lock-a-workspace-to-its-files) |
| 409 | `nothing_to_publish` | Bản nháp đã lưu giống hệt phiên bản đang chạy |
| 409 | `tests_failed` | Publish yêu cầu test phải qua và có một test thất bại |
| 409 | `route_taken` / `route_locked` | Một endpoint khác đã dùng phương thức và đường dẫn này / không đổi được đường dẫn khi đang chạy |
| 409 | `plan_changed` | `apply --expected-digest` không còn khớp với plan |
| 409 | `datasource_in_use` / `secret_in_use` | Vẫn đang được dùng bởi endpoint / datasource hoặc kênh cảnh báo |
