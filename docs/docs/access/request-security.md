---
sidebar_position: 3
---

# Request signing and encryption

Two optional protections per client, set on the service account's page with **Security** next to the client.

## Require signed requests

With **Require signed requests** on, every call from the client must carry an HMAC-SHA256 signature made with a signing
key that OpenDBAPI issues. A call without a valid signature, older than 5 minutes, or replayed, answers
`401 bad_signature`. Nonces are kept in PostgreSQL, so a request accepted by one server instance cannot be replayed to
another.

When you turn it on, OpenDBAPI shows the signing key once; copy it and press **I have stored the key**. **Issue a new
signing key** replaces it; the current key stops working.

The client sends three headers besides the token:

| Header | Value |
|---|---|
| `X-Timestamp` | Milliseconds since 1970; at most 5 minutes off the server's clock |
| `X-Nonce` | A random value of 1 to 128 characters, never reused |
| `X-Signature` | Base64 of HMAC-SHA256(signing key, canonical request) |

The canonical request is six lines joined by `\n`: the method, the request path, the query string as `name=value` pairs
sorted and joined with `&`, the SHA-256 of the body in hex (of an empty body when there is none), the timestamp and the
nonce.

```text
GET
/api/demo/v1/customers
tier=gold
e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
1794930769000
2b1f7c4e-91a0-4d5e-a7a1-3c1f0e8d9b52
```

A Python client:

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

## Encrypt responses

With **Encrypt responses** on, paste the client's RSA **Public key** (PEM). OpenDBAPI then answers with
`{encryptedKey, iv, data}` instead of the usual body, and the `X-OpenDBAPI-Encrypted: aes-256-gcm; key=rsa-oaep-sha256`
header: `data` is AES-256-GCM ciphertext of the normal JSON answer, and `encryptedKey` is the AES key encrypted with
RSA-OAEP (SHA-256). Streaming is refused for such clients (`406 not_acceptable`).
