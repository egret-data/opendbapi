---
sidebar_position: 5
---

# Operations

Monitoring, logs, backups and upgrades.

## Metrics

`/actuator/prometheus` serves Prometheus metrics. Besides the JVM, HTTP server and connection pool metrics, OpenDBAPI
records:

| Metric | What it measures |
|---|---|
| `opendbapi_requests_total` | Calls to published endpoints |
| `opendbapi_request_duration_seconds` | Time to answer a call (histogram) |
| `opendbapi_db_duration_seconds` | Time spent in the endpoint's database (histogram) |
| `opendbapi_pool_active` | Busy connections per datasource |
| `opendbapi_cache_hits_total` / `opendbapi_cache_misses_total` | Answer cache lookups (once an endpoint caches) |
| `opendbapi_access_log_dropped_total` | Activity rows dropped under overload |

## Tracing

OpenDBAPI emits OpenTelemetry traces over OTLP. Set the collector with Spring Boot's
`MANAGEMENT_OTLP_TRACING_ENDPOINT` (e.g. `http://otel-collector:4318/v1/traces`); 10 % of requests are sampled by default
(`OPENDBAPI_TRACE_SAMPLING`). The trace id of a call is stored with it in **Activity**.

## Logs

Logs go to standard output. Statements slower than `opendbapi.serving.slow-query-ms` (1000 ms) are logged with their SQL
but without parameter values. Errors that callers see as `500` are logged with their request id, which callers receive
as `requestId`.

## Backups

Everything OpenDBAPI keeps is in its PostgreSQL database: back it up with your usual PostgreSQL tooling. Keep the master
key with the backup, separately: without it stored secrets cannot be read. [Configuration as
code](../config/yaml-format.md) is a good second copy of the endpoints themselves.

## Upgrades

1. Read the release notes for the new version.
2. Back up the database.
3. Roll out the new image (or `helm upgrade` with the new chart).

The first new pod migrates the schema on start (Flyway, as the owner role) before it reports ready. Downgrades are not
supported: restore the backup instead.
