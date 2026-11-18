---
sidebar_position: 2
---

# Gọi endpoint

Chương trình cần gửi gì để gọi một endpoint đã publish, và nhận lại gì.

## URL {#url}

```text
{base}/api/{workspace}/{path}
```

Ví dụ `https://opendbapi.example.com/api/demo/v1/customers/3`. Tiền tố `api` lấy từ `opendbapi.serving.context`.
Mỗi workspace công bố một tài liệu OpenAPI cho các endpoint đang chạy tại `/api/{workspace}/openapi.json`, xem trên trình
duyệt tại `/api/{workspace}/docs.html`.

## Lấy token {#get-a-token}

Endpoint private cần bearer token. Đổi id và secret của client lấy token bằng OAuth2 client-credentials grant, xác thực
bằng HTTP Basic (hoặc các trường form `client_id` và `client_secret`):

```bash
curl -s -u "$CLIENT_ID:$CLIENT_SECRET" -d grant_type=client_credentials https://opendbapi.example.com/oauth2/token
```

```json
{"access_token": "eyJraWQiOiJi…", "token_type": "Bearer", "expires_in": 900}
```

Token có hiệu lực 15 phút (`opendbapi.tokens.ttl`); hết hạn thì lấy token mới. Sai id hoặc secret sẽ nhận
`401 invalid_client`.

## Gọi {#call}

```bash
curl -s -H "Authorization: Bearer $TOKEN" "https://opendbapi.example.com/api/demo/v1/customers?tier=gold"
```

Endpoint public không cần header `Authorization`.

### Tham số {#parameters}

Truyền tham số đúng chỗ endpoint khai báo: trong path, query string, header, hoặc JSON body
(`Content-Type: application/json`, tối đa 1 MB). Mảng trong query string thì lặp lại tên hoặc phân tách giá trị bằng dấu
phẩy: `?ids=1&ids=2` và `?ids=1,2` là như nhau.

## Phản hồi {#the-response}

```json
{
  "data": [{"id": 1, "name": "Ada Lovelace", "tier": "gold"}],
  "meta": {
    "version": 3,
    "cached": false,
    "page": 1, "size": 20, "total": 2, "hasNext": false,
    "requestId": "cfbf7475-0d34-4002-bfc0-a20e6dbdd64f"
  }
}
```

| Trường | Ý nghĩa |
|---|---|
| `data` | Một danh sách, một object hoặc một giá trị, tuỳ output shape của endpoint. Lệnh ghi trả về `{"affectedRows": n}` |
| `meta.version` | Phiên bản đã publish trả lời request (cũng có trong header `X-OpenDBAPI-Version`) |
| `meta.cached` | Câu trả lời có lấy từ cache hay không |
| `meta.page`, `size`, `total`, `hasNext` | Phân trang theo offset (`total` chỉ có khi endpoint đếm tổng) |
| `meta.nextCursor` | Phân trang keyset: truyền giá trị này vào `after` để lấy trang tiếp theo |
| `meta.truncated` | `true` khi số dòng thực tế nhiều hơn số endpoint trả về |
| `meta.requestId` | Gửi kèm khi báo lỗi; giá trị này cũng có trong **Hoạt động** (và header `X-Request-Id`) |

Endpoint đã deprecated thêm các header `Deprecation`, `Sunset` và `Link`; xem
[Phiên bản major và deprecation](../endpoints/versions-and-majors.md).

### Phân trang {#pages}

```text
?page=2&size=50          offset pagination
?size=50&after=<cursor>  keyset pagination
```

### Streaming {#streaming}

Nếu endpoint cho phép, gửi `?format=ndjson` (mỗi dòng một JSON object) hoặc `?format=csv`, hoặc gửi header `Accept`
tương ứng. Stream không phân trang; stream bị cắt giữa chừng sẽ kết thúc bằng một dòng `{"_meta": …}`.

## Lỗi {#errors}

Lỗi trả về theo dạng problem của RFC 9457, có `code` cố định:

```json
{
  "type": "about:blank",
  "title": "Unauthorized",
  "status": 401,
  "detail": "This endpoint needs an access token: Authorization: Bearer <token>.",
  "code": "unauthenticated",
  "requestId": "dccc29a5-b40d-4a88-b1fc-f6ad41263471"
}
```

[Bảng mã lỗi](../reference/errors.md) liệt kê mọi mã. Phản hồi `429` có header `Retry-After`, phản hồi `401` có header
`WWW-Authenticate`.
