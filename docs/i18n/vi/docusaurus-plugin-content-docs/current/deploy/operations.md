---
sidebar_position: 5
---

# Vận hành

Giám sát, nhật ký, backup và nâng cấp.

## Metrics {#metrics}

`/actuator/prometheus` cung cấp metrics cho Prometheus. Ngoài metrics của JVM, HTTP server và connection pool, OpenDBAPI
ghi lại:

| Metric | Đo gì |
|---|---|
| `opendbapi_requests_total` | Số lượt gọi endpoint đã publish |
| `opendbapi_request_duration_seconds` | Thời gian trả lời một lượt gọi (histogram) |
| `opendbapi_db_duration_seconds` | Thời gian nằm trong database của endpoint (histogram) |
| `opendbapi_pool_active` | Số kết nối đang bận theo từng datasource |
| `opendbapi_cache_hits_total` / `opendbapi_cache_misses_total` | Số lần tra cache câu trả lời (khi endpoint có bật cache) |
| `opendbapi_access_log_dropped_total` | Số dòng hoạt động bị bỏ khi quá tải |

## Tracing {#tracing}

OpenDBAPI phát trace OpenTelemetry qua OTLP. Đặt collector bằng biến của Spring Boot
`MANAGEMENT_OTLP_TRACING_ENDPOINT` (ví dụ `http://otel-collector:4318/v1/traces`); mặc định 10 % request được lấy mẫu
(`OPENDBAPI_TRACE_SAMPLING`). Trace id của mỗi lượt gọi được lưu cùng lượt gọi đó trong **Hoạt động**.

## Nhật ký {#logs}

Nhật ký được ghi ra standard output. Câu lệnh chậm hơn `opendbapi.serving.slow-query-ms` (1000 ms) được ghi kèm SQL nhưng
không kèm giá trị tham số. Lỗi mà người gọi thấy dưới dạng `500` được ghi kèm request id, và người gọi nhận id này trong
`requestId`.

## Backup {#backups}

Mọi thứ OpenDBAPI lưu đều nằm trong database PostgreSQL của nó: hãy backup bằng công cụ PostgreSQL bạn vẫn dùng. Cất
master key cùng bản backup nhưng ở chỗ riêng: thiếu nó thì không đọc được các secret đã lưu. [Cấu hình dạng
code](../config/yaml-format.md) là bản sao thứ hai tốt cho chính các endpoint.

## Nâng cấp {#upgrades}

1. Đọc release notes của phiên bản mới.
2. Backup database.
3. Triển khai image mới (hoặc `helm upgrade` với chart mới).

Pod mới đầu tiên migrate schema khi khởi động (Flyway, bằng role owner) trước khi báo sẵn sàng. Không hỗ trợ hạ phiên
bản: hãy khôi phục bản backup.
