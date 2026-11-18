---
sidebar_position: 3
---

# Ký và mã hoá request

Mỗi client có hai lớp bảo vệ tuỳ chọn, bật trên trang của service account bằng nút **Bảo mật** cạnh client.

## Bắt buộc request có chữ ký {#require-signed-requests}

Khi bật **Bắt buộc request có chữ ký**, mọi lời gọi từ client phải kèm chữ ký HMAC-SHA256 tạo bằng khoá ký do OpenDBAPI
cấp. Lời gọi không có chữ ký hợp lệ, cũ hơn 5 phút, hoặc bị gửi lại sẽ nhận `401 bad_signature`. Nonce được lưu trong
PostgreSQL, nên một request đã được instance này chấp nhận không thể gửi lại sang instance khác.

Khi bạn bật tính năng này, OpenDBAPI hiện khoá ký một lần; sao chép nó rồi bấm **Tôi đã lưu khoá**. **Cấp khoá ký mới**
thay khoá cũ; khoá hiện tại ngừng hoạt động.

Ngoài token, client gửi thêm ba header:

| Header | Giá trị |
|---|---|
| `X-Timestamp` | Số mili giây tính từ 1970; lệch đồng hồ máy chủ không quá 5 phút |
| `X-Nonce` | Một giá trị ngẫu nhiên dài 1 đến 128 ký tự, không bao giờ dùng lại |
| `X-Signature` | Base64 của HMAC-SHA256(khoá ký, canonical request) |

Canonical request gồm sáu dòng nối bằng `\n`: method, path của request, query string dưới dạng các cặp `name=value` đã
sắp xếp và nối bằng `&`, SHA-256 của body ở dạng hex (body rỗng nếu không có body), timestamp và nonce.

```text
GET
/api/demo/v1/customers
tier=gold
e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
1794930769000
2b1f7c4e-91a0-4d5e-a7a1-3c1f0e8d9b52
```

Một client viết bằng Python:

```python
import base64, hashlib, hmac, time, uuid, requests

def call(url_path, query, token, key, body=b""):
    ts, nonce = str(int(time.time() * 1000)), str(uuid.uuid4())
    pairs = "&".join(sorted(f"{k}={v}" for k, v in query.items()))
    canonical = "\n".join(["GET", url_path, pairs, hashlib.sha256(body).hexdigest(), ts, nonce])
    signature = base64.b64encode(hmac.new(key.encode(), canonical.encode(), hashlib.sha256).digest()).decode()
    return requests.get("https://opendbapi.example.com" + url_path, params=query, headers={
        "Authorization": f"Bearer {token}", "X-Timestamp": ts, "X-Nonce": nonce, "X-Signature": signature})
```

## Mã hoá phản hồi {#encrypt-responses}

Khi bật **Mã hoá phản hồi**, dán **Khoá công khai** RSA (PEM) của client. Từ đó OpenDBAPI trả về `{encryptedKey, iv, data}`
thay cho body thông thường, kèm header `X-OpenDBAPI-Encrypted: aes-256-gcm; key=rsa-oaep-sha256`: `data` là bản mã
AES-256-GCM của câu trả lời JSON bình thường, còn `encryptedKey` là khoá AES được mã hoá bằng RSA-OAEP (SHA-256). Client
loại này không dùng được streaming (`406 not_acceptable`).
