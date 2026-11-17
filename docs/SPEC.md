# Đặc tả kỹ thuật: OpenDBAPI (viết lại DBAPI bằng Java Spring Boot)

- **Phiên bản tài liệu:** 0.3 (bản nháp)
- **Ngày:** 2026-11-16
- **Nguồn tham chiếu:**
  - Ý tưởng sản phẩm: DBAPI (nền tảng biến SQL thành API). OpenDBAPI là một thiết kế độc lập, chỉ tương thích về khái niệm.
  - [Egret](https://github.com/egret-data/platform): quy ước UI (`docs/en/frontend-rules.md`) và thiết kế tính năng Data API (`docs/en/specs/2026-08-1*-data-api-*.md`). Backend chỉ tham khảo thiết kế (Rust ↔ Java); frontend dùng lại khung UI theo Apache-2.0.

> **Nhãn nguồn:** **[E]** quyết định thiết kế lấy từ Egret; phần không gắn nhãn là thiết kế của dự án này.

### Thay đổi ở 0.3

- Đã chốt: metadata DB chỉ dùng **PostgreSQL**; đăng nhập bằng **tài khoản nội bộ + LDAP + SSO (OIDC)**; sản phẩm **chạy độc lập**; giao diện tiếng Anh, thêm tiếng Việt ở P2; **đa tenant theo workspace** ngay từ P1.
- Thêm §4.0 (workspace) và §8.5 (nhà cung cấp danh tính). Mọi tài nguyên đều nằm trong một workspace.
- Bỏ `group_member`/`group_datasource`. Quyền của người viết chuyển sang **role theo workspace**. Nhóm chỉ còn là thư mục hiển thị.
- §17 viết lại: UI thiết kế mới, gọn, chỉ cho tính năng DBAPI. Chỉ lấy **khung** của Egret (shell, layout, primitive), không lấy toàn bộ.

### Thay đổi ở 0.2

- Frontend chuyển từ Vue sang **React 19 + Tailwind v4 + Radix**, theo đúng stack và quy ước của Egret (§17).
- Vòng đời API **draft → live → disabled**, mỗi lần publish tạo một phiên bản bất biến (§5.1).
- Xác thực client theo **OAuth2 `client_credentials`** (service account → client), bỏ token tự chế (§8).
- Thêm loại task **Groovy `SqlFragment`** (§7.4).
- Thêm: tự sinh OpenAPI, phân trang có sẵn, map kết quả, kiểm tra SQL tĩnh, bộ test cho từng API, cấu hình dưới dạng code, che/lọc dữ liệu, quan sát hệ thống, trả kết quả dạng stream, quản lý phiên bản API, tích hợp Vault, xuất tool MCP.
- Admin REST chuyển sang dạng resource RESTful, sinh client tự động bằng Orval. **Bỏ** việc tương thích route với UI gốc.

---

## 1. Mục tiêu và phạm vi

### 1.1 Mục tiêu

Cho phép người dùng viết SQL / script / Elasticsearch DSL / HTTP proxy trên giao diện web, kiểm thử, rồi **publish thành REST API có phiên bản**. Người dùng không cần viết code hay deploy lại. Bên gọi API xác thực bằng OAuth2.

### 1.2 Khác biệt chính so với DBAPI

| Hạng mục | DBAPI | OpenDBAPI |
|---|---|---|
| Vòng đời API | online/offline, sửa trực tiếp bản đang chạy | Draft tách khỏi bản live, publish tạo phiên bản bất biến, có rollback |
| Sinh SQL | XML kiểu MyBatis | XML kiểu MyBatis **và** Groovy `SqlFragment` |
| Tài liệu API | Export Markdown | OpenAPI 3 tự sinh, kèm Swagger UI |
| Phân trang | Tự viết 2 câu SQL | Khai báo `pageable`, engine tự sinh |
| Kết quả | Trả nguyên dòng phẳng | Đổi tên cột, gộp lồng nhau, ép kiểu |
| Xác thực | Token tự chế | OAuth2 client-credentials (JWT), tuỳ chọn ký HMAC |
| Kiểm thử | Nút chạy thử | Try-it chạy qua pipeline thật, bộ test case chạy được trong CI |
| Môi trường | Một | dev/staging/prod, cấu hình lưu YAML trong Git |
| Bảo mật dữ liệu | Không có | Che dữ liệu theo cột, lọc dòng theo client |
| Quan sát hệ thống | Access log + dashboard | OpenTelemetry, Prometheus, log query chậm |
| Kết quả lớn | Nạp hết vào RAM | Trả dạng stream (JSON/CSV/NDJSON) |
| Bí mật | Lưu trong DB | Mã hoá AES-GCM, hoặc tham chiếu Vault / biến môi trường |
| AI | Không có | Xuất API thành tool MCP |

### 1.3 Phạm vi theo giai đoạn

| Giai đoạn | Nội dung |
|---|---|
| **P1 – MVP** | **Workspace (đa tenant)**; đăng nhập **nội bộ + LDAP + OIDC**; datasource JDBC; task SQL template; draft/publish/rollback; nhóm API; service account + OAuth2; Try-it; **OpenAPI**; **phân trang**; **kiểm tra SQL tĩnh**; **quan sát hệ thống**; **virtual threads + stream**; access log ra file + bảng Activity cơ bản; UI React |
| **P2 – Đầy đủ** | **Task Groovy `SqlFragment`**; **map kết quả**; **ký HMAC**; **bộ test cho từng API**; **che/lọc dữ liệu**; rule tham số; cảnh báo lỗi qua webhook; cache kết quả; QPS; firewall; ES & HTTP executor; mã hoá response; thống kê đầy đủ; import từ DBAPI |
| **P3 – Mở rộng** | **Cấu hình dưới dạng code và đa môi trường**; **phiên bản major song song**; **MCP**; Vault; workflow DAG; chế độ cluster (gateway, Redis) |

### 1.4 Ngoài phạm vi

- Cơ chế plugin (SPI, nạp jar ngoài). Cần thêm hành vi gì thì viết thẳng thành tính năng của hệ thống (quyết định 2026-11-17, §13).
- Cổng tài liệu riêng cho người gọi API bên ngoài. Người gọi nhận URL và file OpenAPI tải về [E].

---

## 2. Stack công nghệ

### 2.1 Backend

| Hạng mục | Lựa chọn |
|---|---|
| Ngôn ngữ / runtime | Java 21 LTS, **virtual threads** (`spring.threads.virtual.enabled=true`) |
| Framework | Spring Boot 3.3.x, Spring Security, Spring Authorization Server (OAuth2) |
| Build | Maven multi-module |
| Metadata DB | **Chỉ PostgreSQL 15+** (dev chạy bằng Docker Compose / Testcontainers); migration bằng **Flyway**. Datasource *người dùng* vẫn là bất kỳ DB nào có driver JDBC |
| Xác thực người viết | Spring Security: form login (tài khoản nội bộ), `spring-security-ldap`, `oauth2-client` (OIDC) |
| Truy cập metadata | Spring `JdbcClient` |
| Pool datasource người dùng | HikariCP, một pool / datasource |
| Phân tích SQL | **JSqlParser** (kiểm tra tĩnh, phân trang, che dữ liệu) |
| SQL template | Engine tự viết, cú pháp MyBatis XML (§7.2) |
| Script | Groovy 4 (§7.4), tuỳ chọn GraalJS ở P3 |
| OpenAPI | springdoc-openapi cho admin API; tự sinh động cho API người dùng (§10) |
| Rate limit | Bucket4j (in-memory, Redis ở chế độ cluster) |
| Cache | Caffeine; Redis ở chế độ cluster |
| Quan sát hệ thống | Micrometer + Prometheus, OpenTelemetry (OTLP) |
| Bí mật | AES-GCM nội bộ; Spring Cloud Vault (P3) |
| MCP | MCP Java SDK, transport Streamable HTTP (P3) |
| Test | JUnit 5, Testcontainers (PostgreSQL, MySQL, ES), MockMvc |

### 2.2 Frontend [E]

Theo đúng stack của Egret để dùng lại quy ước và component:

| Hạng mục | Lựa chọn |
|---|---|
| Framework | React 19, TypeScript, Vite |
| Style | Tailwind CSS v4 với design token (`@theme`), chế độ sáng/tối |
| Component | Radix UI (kiểu shadcn): button, dialog, select, tabs, table… |
| Dữ liệu | TanStack Query; client API **sinh bằng Orval** từ `openapi.json` của backend |
| Bảng | TanStack Table |
| Router | react-router-dom 7, lazy route theo khu vực tính năng |
| Editor | Monaco (SQL, Groovy, JSON, YAML), nạp lazy |
| Biểu đồ | ECharts |
| Icon / thông báo | lucide-react / sonner |
| Test | Vitest + Testing Library, Playwright |

### 2.3 Cấu trúc repo

```text
opendbapi/
├── backend/
│   ├── opendbapi-sql/          # SQL template, ngôn ngữ biểu thức, kiểm tra tĩnh, phân trang (Java thuần)
│   ├── opendbapi-core/         # domain, store, executor, pipeline
│   ├── opendbapi-admin/        # REST quản trị /admin/api/**
│   ├── opendbapi-serving/      # /{context}/**, OAuth2 token, MCP
│   ├── opendbapi-cli/          # (P3) lệnh plan/apply cấu hình dưới dạng code
│   └── opendbapi-app/          # Spring Boot app gộp (standalone)
├── frontend/                   # React app
├── openapi.json                # spec admin API đã commit, đầu vào của Orval
└── docs/
```

---

## 3. Kiến trúc

### 3.1 Hai router tách biệt [E]

| Router | Tiền tố | Xác thực | Mục đích |
|---|---|---|---|
| Management | `/admin/api/**` (tài nguyên của workspace nằm dưới `/admin/api/ws/{ws}/**`) | Session cookie + CSRF | Viết, publish, quản lý |
| Serving | `/{context}/{ws}/**` (mặc định `/api/{ws}/…`), `/oauth2/token`, `/{context}/{ws}/mcp` | Chỉ nhận Bearer token của client | Phục vụ bên gọi API |

Serving **không** nhận session cookie. Nhờ vậy, việc người dùng đăng nhập UI không vô tình mở được API dữ liệu.

`{ws}` là slug của workspace (vd `sales`). Slug là một phần của URL công khai nên **không đổi được** sau khi tạo; đổi tên hiển thị thì được. Ví dụ URL đầy đủ: `GET /api/sales/v1/users/42`.

### 3.2 Chế độ chạy

- **Standalone:** một tiến trình phục vụ UI tĩnh, management và serving.
- **Cluster (P3):** tách `admin` và `serving` (N bản sao); Redis lưu cache, rate limit, và pub/sub `endpoint.changed`. Gateway là tuỳ chọn; có thể dùng ingress có sẵn.

### 3.3 Registry

- `EndpointRegistry` giữ `Map<(workspaceId, method, path), CompiledEndpoint>`, chỉ gồm endpoint ở trạng thái `live`, chưa vào thùng rác, và trỏ tới **phiên bản đang live** (không phải bản draft).
- `CompiledEndpoint` chứa: snapshot phiên bản, template/script đã biên dịch, tham số đã chuẩn hoá, cấu hình phân trang/map/che dữ liệu/rule tham số.
- Nạp lại khi publish, rollback, disable, đổi datasource, hoặc nhận pub/sub (cluster).

---

## 4. Mô hình dữ liệu

Schema mới trong PostgreSQL, schema `opendbapi`. Dữ liệu DBAPI được nhập qua importer (§15.3), không giữ schema y hệt. ID dùng UUID v7. Thời gian lưu kiểu `timestamptz`. Cấu hình lưu `jsonb`.

### 4.0 Đa tenant theo workspace

- Workspace là ranh giới cách ly. **Mọi** bảng nghiệp vụ (trừ `users`, `identity_provider`, `workspace`, `audit_log` cấp hệ thống) đều có cột `workspace_id NOT NULL`, và các ràng buộc duy nhất đều tính trong phạm vi workspace (vd `UNIQUE (workspace_id, method, path)`).
- **Hai lớp cách ly:**
  1. Tầng ứng dụng: mọi repository nhận `WorkspaceContext` và luôn lọc theo `workspace_id`. Thiếu context thì ném lỗi, không bao giờ lấy mặc định "tất cả".
  2. Tầng DB: bật **PostgreSQL Row-Level Security** trên các bảng nghiệp vụ, policy `workspace_id = current_setting('opendbapi.workspace_id')::uuid`. Ứng dụng đặt biến này bằng `SET LOCAL` ở đầu mỗi transaction. Nhờ vậy, một bug quên điều kiện lọc cũng không đọc được dữ liệu workspace khác. Tác vụ hệ thống (migration, dọn log) dùng role DB riêng có `BYPASSRLS`.
- Datasource, endpoint, service account, data policy, rate limit, firewall, môi trường và access log đều thuộc về một workspace. **Không chia sẻ datasource giữa các workspace** (muốn dùng chung thì tạo lại, secret có thể cùng trỏ một `vault:` hoặc `env:`).
- Token OAuth2 mang claim `ws`. Serving từ chối token có `ws` khác với `{ws}` trên URL (403 `wrong_workspace`).
- Hạn mức theo workspace (cấu hình được): số endpoint, số datasource, tổng QPS.
- Xoá workspace: chỉ admin hệ thống, phải xác nhận bằng cách gõ slug. Workspace chuyển sang `archived` (ngừng phục vụ ngay), xoá hẳn sau 30 ngày.

### 4.1 Danh sách bảng

| Bảng | Mục đích | Giai đoạn |
|---|---|---|
| `workspace` | `id, slug UNIQUE, name, status ('active'\|'archived'), quotas jsonb, created_at` | P1 |
| `users` | Người dùng: `id, username, email, display_name, source ('local'\|'ldap'\|'oidc'), idp_id, external_id, password_hash NULL, system_admin bool, disabled` | P1 |
| `identity_provider` | Cấu hình LDAP / OIDC (§8.5) | P1 |
| `workspace_member` | `workspace_id, user_id, role` | P1 |
| `group_role_mapping` | Nhóm LDAP/OIDC → (workspace, role) | P1 |
| `api_group` | Nhóm hiển thị (thư mục), **không** là phần của URL [E] | P1 |
| `datasource` | JDBC / ES / HTTP | P1 |
| `endpoint` | Bản draft, sửa tự do | P1 |
| `endpoint_version` | Snapshot bất biến mỗi lần publish | P1 |
| `endpoint_test_case` | Test case của API | P2 |
| `service_account` | Danh tính của bên gọi | P1 |
| `oauth_client` | Credential của service account | P1 |
| `endpoint_grant` | Service account → endpoint/nhóm | P1 |
| `data_policy` | Quy tắc che cột / lọc dòng | P2 |
| `rate_limit` | QPS theo endpoint / service account | P2 |
| `ip_rule` | Firewall | P2 |
| `access_log` | Log truy cập, partition theo tháng (có thể dùng DB riêng) | P1 |
| `audit_log` | Ai làm gì: publish, rollback, Try-it, cấp quyền | P1 |
| `environment` | dev/staging/prod và cách gắn datasource theo môi trường | P3 |

### 4.2 `endpoint` (draft)

| Cột | Kiểu | Ghi chú |
|---|---|---|
| `id` | uuid PK | |
| `workspace_id` | uuid NOT NULL | |
| `group_id` | uuid NULL | |
| `name`, `description` | text | |
| `method` | text | `GET` / `POST` / `PUT` / `DELETE` |
| `path` | text | Tương đối so với `/{context}/{ws}`, vd `v1/users/{id}`. Duy nhất theo (`workspace_id`, `method`, `path`) |
| `state` | text | `draft` / `live` / `disabled` [E] |
| `live_version_id` | uuid NULL | Phiên bản đang phục vụ |
| `access` | text | `private` (cần token) / `public` |
| `definition` | jsonb | Toàn bộ cấu hình draft (§5.2) |
| `dirty` | bool | Draft khác với phiên bản đang live |
| `deleted_at` | timestamptz NULL | Thùng rác |
| `created_by`, `created_at`, `updated_at` | | |

### 4.3 `endpoint_version`

| Cột | Kiểu | Ghi chú |
|---|---|---|
| `id` | uuid PK | |
| `endpoint_id` | uuid | |
| `version` | int | Tăng dần, bắt đầu từ 1 |
| `definition` | jsonb | Snapshot đóng băng |
| `output_schema` | jsonb | Tên và kiểu cột, chốt lại khi validate (dùng cho OpenAPI) |
| `published_by`, `published_at`, `note` | | |

### 4.4 Client và quyền

- `service_account(id, name, description, disabled, created_by, created_at)`
- `oauth_client(id, service_account_id, client_id UNIQUE, secret_hash, hmac_required bool, public_key text NULL, response_encrypt bool, expires_at NULL, last_used_at, revoked_at NULL)`
- `endpoint_grant(service_account_id, resource_type 'endpoint'|'group', resource_id, granted_by, granted_at)`

### 4.5 Các bảng khác

- `datasource(id, name, type 'jdbc'|'elasticsearch'|'http', config jsonb, secret_ref text, read_only bool, ...)`: `secret_ref` có dạng `enc:<AES-GCM>`, `env:NAME`, hoặc `vault:path#key`.
- `data_policy(id, datasource_id NULL, endpoint_id NULL, kind 'mask'|'row_filter', column text NULL, rule jsonb)`
- `access_log(id, ts, endpoint_id, version, method, path, status, duration_ms, rows, client_id, service_account_id, ip, error_code, error, trace_id, actor_user_id NULL)`: `actor_user_id` khác NULL nghĩa là lời gọi đến từ Try-it, không tính vào thống kê [E].
- `audit_log(id, ts, user_id, action, target_type, target_id, detail jsonb)`

### 4.6 Cấu hình datasource

```jsonc
// type = jdbc (P1)
{
  "dialect": "POSTGRESQL",             // POSTGRESQL | MYSQL | MARIADB | SQLSERVER | ORACLE
  "host": "db.corp.local",             // chỉ tên host hoặc IP, không kèm port/path/tham số
  "port": null,                        // null = cổng mặc định của dialect
  "database": "sales",                 // Oracle: service name
  "username": "api_reader",
  "properties": { "sslmode": "require" },   // chỉ các thuộc tính trong danh sách trắng của dialect
  "pool": { "maximumPoolSize": 10, "minimumIdle": 0, "connectionTimeoutMs": 10000 }
}
// type = elasticsearch (P2)
{ "hosts": ["http://es1:9200"], "username": "elastic" }
// type = http (P2)
{ "baseUrl": "https://upstream.example.com", "headers": { "X-Source": "opendbapi" } }
```

Mật khẩu không nằm trong `config` mà ở `secret_ref`. API không bao giờ trả mật khẩu về UI.

**Bảo mật datasource.** Datasource do admin của *workspace* (không phải admin hệ thống) cấu hình, nên chuỗi kết nối được coi là đầu vào không tin cậy:

- **Không cho nhập URL JDBC thô.** Server tự dựng URL từ các trường có cấu trúc; host, database và giá trị thuộc tính bị kiểm tra để không chèn được `?`, `;`, `&` hay `/`.
- **Danh sách trắng thuộc tính driver theo dialect.** Những thuộc tính khiến driver nạp class, đọc file hoặc deserialize đều bị từ chối, ví dụ PostgreSQL `socketFactory`/`sslfactory` (lớp lỗ hổng CVE-2022-21724), MySQL `autoDeserialize`/`allowLoadLocalInfile`. Mỗi kết nối còn luôn kèm thuộc tính an toàn: MySQL `allowLoadLocalInfile=false`, `autoDeserialize=false`; MariaDB `allowLocalInfile=false`.
- **Chỉ có driver PostgreSQL, MySQL, MariaDB, SQL Server, Oracle.** Không có H2, SQLite hay các DB dạng file/chạy trong tiến trình, vì URL của chúng đọc được file hoặc chạy được script trên server. ClickHouse để P2.
- **Chặn host.** Mặc định chặn endpoint metadata của cloud (`169.254.0.0/16`, `fd00:ec2::254`, `100.100.100.200`, `metadata.google.internal`). Operator thêm được bằng `opendbapi.datasources.extra-denied-hosts`, hoặc giới hạn bằng `allowed-hosts` (tên host, `*.suffix`, CIDR). Tên host được resolve trước khi so khớp. DNS vẫn có thể đổi giữa lúc kiểm tra và lúc kết nối, nên network policy là lớp bảo vệ chính; đây là lớp bổ sung.
- **Nên đặt database khách hàng ở server khác với metadata DB của OpenDBAPI.** Nếu dùng chung một server PostgreSQL, tài khoản của datasource vẫn *nhìn thấy tên* các bảng metadata (driver liệt kê mọi bảng bất kể quyền), dù không đọc được dữ liệu nhờ phân quyền và RLS. Khi buộc phải dùng chung, thêm host đó vào `extra-denied-hosts`.
- **`read_only`:** chặn publish SQL có ghi dữ liệu (§7.5) và mở session ở chế độ chỉ đọc. Với PostgreSQL điều này thật sự được ép (`readOnlyMode=always`), vì `setReadOnly` của JDBC không có tác dụng khi autocommit bật. MySQL/MariaDB được ép bằng `SET SESSION TRANSACTION READ ONLY` mỗi khi mở kết nối (driver MariaDB không đẩy `setReadOnly` xuống server). Cả ba đã được kiểm chứng trên DB thật (`ReadOnlyEnforcementTest`). SQL Server và Oracle không ép được ở mức session, nên bảo đảm thật sự vẫn là một tài khoản DB chỉ có quyền đọc.
- **Pool:** HikariCP, tối đa 50 kết nối, timeout lấy kết nối 250 ms–60 s. Pool được tạo lần đầu khi dùng và đóng/tạo lại khi datasource đổi hoặc bị xoá.

---

## 5. Định nghĩa endpoint

### 5.1 Vòng đời [E]

```text
         save (draft)                    publish (validate → v N)
create ───────────────▶ draft ──────────────────────────────────▶ live
                          ▲                                       │  │
                          │      sửa draft (dirty = true) ◀───────┘  │ disable
                          │                                          ▼
                          └──────────── trash ◀────────────────── disabled
                                          │  restore → disabled      │ enable
                                          ▼                          └──▶ live
                                   xoá vĩnh viễn

rollback(v K): live_version_id := v K (K < N), không đổi draft
```

- **Publish** = validate (biên dịch template/script, kiểm tra SQL tĩnh, chạy thử với test case mẫu nếu có, chốt `output_schema`) rồi tạo `endpoint_version` bất biến.
- Sửa endpoint đang live chỉ sửa bản draft. Bên gọi vẫn được phục vụ bằng phiên bản cũ cho tới lần publish tiếp theo.
- **Chỉ endpoint `draft` hoặc `disabled` mới được đưa vào thùng rác.** Server từ chối với endpoint đang live; chặn ở API chứ không chỉ ẩn nút trên UI [E].
- Truy vấn của serving loại trừ `deleted_at IS NOT NULL` [E].
- Restore từ thùng rác trả endpoint về `disabled`, không trả thẳng về `live` [E].

### 5.2 `definition` (JSON)

```jsonc
{
  "params": [
    { "name": "id", "in": "path", "type": "bigint", "required": true },
    { "name": "status", "in": "query", "type": "string", "enum": ["active", "locked"] },
    { "name": "from", "in": "query", "type": "date", "required": false, "default": null }
  ],
  "body": null,                        // JSON Schema khi method là POST/PUT
  "task": { /* §6 */ },
  "pagination": { "enabled": true, "defaultSize": 20, "maxSize": 500, "mode": "offset" },
  "output": {
    "naming": "camelCase",             // as_is | camelCase
    "shape": "list",                   // list | single | scalar
    "nest": [ /* §9.2 */ ],
    "types": { "amount": "decimal_string", "created_at": "iso8601" }
  },
  "streaming": { "allowed": true, "formats": ["ndjson", "csv"] },
  "cache": { "enabled": false, "ttlSeconds": 60, "scope": "client" },
  "limits": { "timeoutSeconds": 30, "maxRows": 10000 },
  "alarm": { "enabled": false },      // §13.2
  "rules": [ /* §13.3 */ ]
}
```

Request tạo/sửa endpoint gửi trường lạ (kể cả trong object lồng nhau) sẽ bị trả **400**. Không âm thầm bỏ qua [E].

### 5.3 Tham số

- `in`: `path` / `query` / `header` / `body`.
- `type`: `string`, `bigint`, `double`, `decimal`, `boolean`, `date`, `datetime`, `array<string|bigint|double|date>`.
- Ràng buộc: `required`, `default`, `enum`, `min`/`max`, `pattern`, `maxItems`.
- Ép kiểu thất bại hoặc tham số query không được khai báo sẽ trả 400 `invalid_param`, kèm tên tham số.
- Tham số phân trang `page`, `size`, `sort` được dành riêng khi `pagination.enabled`.

---

## 6. Task

| `type` | Mô tả | Giai đoạn |
|---|---|---|
| `sql` | Một hoặc nhiều câu SQL template (XML) | P1 |
| `script` | Groovy trả `SqlFragment`, hoặc script đầy đủ dùng `db.*` | P2 |
| `elasticsearch` | DSL template | P2 |
| `http` | Proxy sang upstream | P2 |
| `workflow` | DAG nhiều nút | P3 |

```jsonc
// sql
{ "type": "sql", "datasourceId": "…", "transaction": false,
  "statements": [ { "id": "main", "template": "select … <where>…</where>" } ] }
// script
{ "type": "script", "datasourceId": "…", "language": "groovy", "mode": "fragment",
  "source": "def q = sql\"select …\" …; return q" }
// elasticsearch
{ "type": "elasticsearch", "datasourceId": "…", "method": "POST",
  "endpoint": "/orders/_search", "template": "{ \"query\": … }" }
// http
{ "type": "http", "datasourceId": "…", "method": "GET", "path": "/v1/orders",
  "forwardParams": ["status", "from"] }
```

Nhiều câu SQL chạy tuần tự trên cùng một connection. Với `transaction: true`, chúng nằm trong một transaction. Kết quả trả về là của câu có `id = "main"`, hoặc của câu cuối cùng.

---

## 7. Sinh SQL

### 7.1 Nguyên tắc chung

- Mọi giá trị từ request đều đi qua `PreparedStatement` (placeholder `?`).
- Không có đường nào nối chuỗi thô từ input vào SQL, trừ các vị trí identifier (tên cột, ORDER BY) phải tra qua whitelist khai báo sẵn.
- Preview (§11.1) dùng **cùng** hàm render với serving, không có phiên bản thứ hai [E].

### 7.2 Template XML kiểu MyBatis

| Thẻ | Ghi chú |
|---|---|
| `<if test>` | |
| `<where>`, `<set>`, `<trim>` | |
| `<foreach collection item index open close separator>` | |
| `<choose>/<when>/<otherwise>` | |
| `<bind name value>` | |

- `#{x}` → `?`. Hỗ trợ đường dẫn (`#{filter.from}`, `#{ids[0]}`) và bỏ qua gợi ý kiểu kiểu MyBatis (`#{d, jdbcType=DATE}`).
- `#{x}` với `x` là danh sách/mảng tự bung thành `?, ?, ?`, nên `id in (#{ids})` dùng được ngay. Danh sách rỗng thành `NULL` (`id in (NULL)` hợp lệ và không khớp dòng nào), thay vì `in ()` gây lỗi cú pháp.
- **Không hỗ trợ `${}`**: compile báo lỗi. Sắp xếp động dùng `<orderBy param="sort" allow="created_at, name" default="created_at desc"/>`: chỉ nhận cột nằm trong `allow` (không phân biệt hoa thường, xuất ra đúng tên trong `allow`), kèm `asc|desc`, nhiều cột cách nhau bằng dấu phẩy. `default` được kiểm tra ngay lúc compile.
- Viết thẳng `<`, `<=`, `<>`, `&&` trong SQL và trong `test` mà không cần `&lt;`/CDATA: engine tự escape mọi `<` không mở một thẻ của engine. CDATA và entity vẫn dùng được.
- Thẻ gõ sai (`<iff test="…">`) báo lỗi kèm số dòng, không bị coi là chữ SQL. DOCTYPE/ENTITY bị từ chối (chặn XXE).
- **Biểu thức `test`/`bind` dùng ngôn ngữ riêng, không dùng SpEL.** Muốn gọi được `ids.size()` thì SpEL phải bật gọi method trên object, mà như vậy mở ra đường `x.getClass().forName(…)`. Ngôn ngữ riêng (`opendbapi-sql/expr`) chỉ có:
  - đọc biến và key/index của Map/List (không gọi getter Java);
  - so sánh `== != < <= > >=` và alias OGNL `eq neq lt lte gt gte`; so sánh số theo `BigDecimal`, `status == 1` khớp `"1"`;
  - `and or not && || !`, phép toán `+ - * / %` (cộng với chuỗi thì nối chuỗi, `null` thành chuỗi rỗng);
  - chỉ ba method `size()`, `length()`, `isEmpty()`. Gọi method khác, `new`, `T(...)` đều là lỗi cú pháp.
  - Giá trị đúng/sai: `null`, `false`, `0`, `""`, collection rỗng đều là false.

### 7.3 Kiến trúc chung

```text
Template XML ─parse─▶ SqlNode AST ─┐
Groovy script ─compile─▶ Script ───┼─▶ render(ctx) ─▶ BoundSql { sql, params[] }
                                   │
              Pagination / RowFilter / Masking (§9) ─▶ BoundSql cuối ─▶ JDBC
```

### 7.4 Groovy `SqlFragment` (P2)

**Chế độ `fragment`:** script trả về `SqlFragment`. Mỗi giá trị nội suy `${…}` bên trong `sql"…"` trở thành tham số bind.

```groovy
def q = sql"select id, name, created_at from users where 1 = 1"

if (params.name)  q += sql" and name ilike ${'%' + params.name + '%'}"
if (params.ids)   q += sql" and id in (${params.ids})"          // collection → ?,?,?
if (params.from)  q += sql" and created_at >= ${params.from}"

def sortable = [newest: 'created_at desc', name: 'name asc']
q += orderBy(sortable, params.sort, 'id desc')                  // chỉ chọn trong map

return q
```

Quy tắc bắt buộc:

1. Kết quả phải là `SqlFragment`. Trả về `String` hay `GString` trần sẽ báo lỗi validate, publish bị từ chối.
2. `SqlFragment + String` sẽ ném lỗi. SQL thô chỉ chèn được qua `raw("…")`, và phần kiểm tra lúc lưu từ chối `raw()` có đối số không phải hằng chuỗi.
3. Phân trang, lọc dòng và che dữ liệu vẫn áp dụng lên kết quả của script, vì chúng nằm sau bước render (§7.3).

**Chế độ `full`** (chỉ role `script_author`):

```groovy
def user = db.one(sql"select * from users where id = ${params.id}")
if (!user) return notFound("User không tồn tại")
def orders = db.list(sql"select * from orders where user_id = ${user.id} order by id desc limit 10")
return [user: user, recentOrders: orders]
```

`db` chỉ nhận `SqlFragment` và luôn dùng đúng datasource của endpoint.

**Bảo mật script.** Sandbox của Groovy **không phải ranh giới bảo mật tin cậy được**. `SecureASTCustomizer` có thể bị vượt qua bằng metaprogramming. Vì vậy:

- Chỉ người có role `script_author`, `admin` hoặc `owner` trong workspace (§8.4) được tạo hoặc sửa task `script`. Kiểm tra quyền ở API.
- Vẫn bật các lớp phòng thủ: `SecureASTCustomizer` (chặn `System`, `Runtime`, `ProcessBuilder`, reflection, `java.io/nio/net`, `Thread`), whitelist import, `TimedInterrupt` (mặc định 2 giây), giới hạn số dòng `db.list`.
- Khuyến nghị dùng tài khoản DB có quyền tối thiểu cho datasource. Datasource có `read_only = true` thì từ chối DML ở tầng kiểm tra tĩnh.
- Biên dịch một lần khi validate/publish và cache theo phiên bản. Mỗi phiên bản dùng một `GroovyClassLoader` riêng và giải phóng khi phiên bản bị thay, để tránh rò rỉ Metaspace.
- P3: cân nhắc GraalJS với quyền truy cập host bị tắt, nếu cần mở cho người dùng không tin cậy.

### 7.5 Kiểm tra SQL tĩnh khi validate (P1)

Phân tích câu SQL đã render với tham số mẫu bằng JSqlParser:

| Quy tắc | Mức |
|---|---|
| Endpoint `GET` chứa INSERT/UPDATE/DELETE/MERGE/DDL | Lỗi |
| Datasource `read_only` mà có DML/DDL | Lỗi |
| `UPDATE`/`DELETE` không có `WHERE` | Lỗi |
| Nhiều câu lệnh trong một statement (`;`) | Lỗi |
| `SELECT *` | Cảnh báo (`output_schema` có thể đổi ngầm) |
| Không phân tích được (cú pháp riêng của DB) | Cảnh báo, cho publish nếu người viết xác nhận. **Nhưng** nếu trong SQL (đã bỏ chuỗi và comment) có từ khoá ghi dữ liệu như `DELETE`, `UPDATE`, `DROP`… thì vẫn là Lỗi ở GET/datasource chỉ đọc. Ví dụ: CTE ghi dữ liệu của PostgreSQL `WITH d AS (DELETE …) SELECT …`, mà JSqlParser 5.0 không parse được |
| DDL (`CREATE/ALTER/DROP/TRUNCATE…`) | Cảnh báo (ngay cả ở POST) |
| `SELECT … INTO` | Tính là ghi dữ liệu |
| `SELECT … FOR UPDATE/SHARE` | Cảnh báo (khoá dòng tới hết request) |

---

## 8. Xác thực và phân quyền

### 8.1 Bên gọi API: OAuth2 client credentials [E]

1. Admin tạo **service account** (danh tính) và cấp quyền lên endpoint hoặc nhóm.
2. Admin tạo **client** cho service account. Secret chỉ hiện **một lần**, trên trang tạo client, kèm nút copy và lời nhắc rằng không xem lại được [E].
3. Bên gọi lấy token: `POST /oauth2/token` với `grant_type=client_credentials` → access token JWT (RS256, mặc định 15 phút, `sub` = service account, `cid` = client).
4. Gọi API kèm `Authorization: Bearer <jwt>`. Token mang claim `ws`; serving từ chối nếu khác workspace trên URL (§4.0).
5. Kiểm tra quyền: có `endpoint_grant` trên endpoint, hoặc trên nhóm chứa endpoint đó.
6. Thu hồi client bằng `revoked_at` (token đang còn hạn bị từ chối nhờ kiểm tra `cid` có cache ngắn). Vô hiệu cả service account bằng `disabled`.

Endpoint `public` bỏ qua bước 4 và 5, nhưng vẫn qua firewall và rate limit theo IP.

### 8.2 Ký request HMAC (P2, tuỳ chọn theo client)

Khi `oauth_client.hmac_required = true`, request phải có thêm:

```text
X-Timestamp: 1763280000000
X-Nonce: 3f1c…
X-Signature: base64(HMAC-SHA256(secret, METHOD \n PATH \n sortedQuery \n sha256(body) \n timestamp \n nonce))
```

Timestamp lệch quá 5 phút hoặc nonce dùng lại (lưu nonce 10 phút) sẽ trả 401 `bad_signature`.

### 8.3 Mã hoá response (P2)

Khi `oauth_client.response_encrypt = true`: body được mã hoá AES-256-GCM, khoá AES mã hoá bằng `public_key` của client (RSA-OAEP-SHA256). Định dạng trả về: `{encryptedKey, iv, data}`.

### 8.4 Người viết (admin UI): role và quyền

Hai cấp quyền:

- **Hệ thống:** cờ `users.system_admin`. Được quản lý workspace, user, nhà cung cấp danh tính. Admin hệ thống đầu tiên được tạo với mật khẩu ngẫu nhiên in ra log lần chạy đầu.
- **Workspace:** mỗi thành viên có một role trong workspace đó.

| Quyền | `owner` | `admin` | `script_author` | `author` | `viewer` |
|---|---|---|---|---|---|
| Xem endpoint, datasource, activity | ✓ | ✓ | ✓ | ✓ | ✓ |
| Tạo/sửa endpoint SQL template, preview | ✓ | ✓ | ✓ | ✓ | |
| Tạo/sửa task Groovy (§7.4) | ✓ | ✓ | ✓ | | |
| Publish / disable / rollback | ✓ | ✓ | ✓ | ✓ | |
| Try-it | ✓ | ✓ | ✓ | ✓ | |
| Quản lý datasource | ✓ | ✓ | | | |
| Service account, client, cấp quyền | ✓ | ✓ | | | |
| Data policy, firewall, rate limit, môi trường | ✓ | ✓ | | | |
| Thành viên & role | ✓ | ✓ (trừ `owner`) | | | |
| Đổi tên, hạn mức, archive workspace | ✓ | | | | |

- Quyền được kiểm tra trong code ở mọi action (`WorkspaceAccess.require(ws, role)`), không chỉ ẩn nút trên UI. Workspace không tồn tại hoặc người gọi không phải thành viên đều trả **404** như nhau, để không dò được workspace nào tồn tại.
- Admin hệ thống được coi là `owner` của mọi workspace đang hoạt động (để tạo thành viên đầu tiên, xử lý sự cố), nhưng `/me` chỉ liệt kê workspace họ thật sự là thành viên.
- Session chỉ lưu id người dùng; trạng thái disabled, cờ admin hệ thống và role được đọc lại từ DB ở mỗi request, nên thay đổi của admin có hiệu lực ngay.
- Một user có thể thuộc nhiều workspace với role khác nhau. Session lưu workspace đang chọn, nhưng mọi admin API vẫn mang `{ws}` trên URL; không dựa vào trạng thái ẩn trong session.
- Session cookie HttpOnly + SameSite=Lax + CSRF token. Hết hạn sau 8 giờ không hoạt động.

### 8.5 Nhà cung cấp danh tính (P1)

Ba nguồn đăng nhập, bật/tắt độc lập. Màn hình login hiển thị các nguồn đang bật.

| Nguồn | Cách hoạt động |
|---|---|
| **Nội bộ** | Username + mật khẩu BCrypt. Chính sách mật khẩu cấu hình được (độ dài, hết hạn). Khoá tạm sau 5 lần sai. Có thể tắt hoàn toàn khi đã có LDAP/SSO (trừ tài khoản break-glass của admin hệ thống) |
| **LDAP / Active Directory** | Bind bằng tài khoản dịch vụ → tìm user theo `userSearchFilter` → bind bằng mật khẩu người dùng. Đọc nhóm theo `groupSearchBase`/`groupSearchFilter` (hỗ trợ `memberOf` của AD). LDAPS hoặc StartTLS là bắt buộc; `ldap://` thường chỉ chạy khi bật `allowInsecure` (chỉ dùng cho dev). Lỗi kết nối ở màn hình login chỉ hiện thông báo chung, chi tiết chỉ có trong nút Test của admin |
| **SSO (OIDC)** | Authorization Code + PKCE. Hỗ trợ Keycloak, Azure AD/Entra ID, Google, Okta… Lấy nhóm từ claim cấu hình được (mặc định `groups`). SAML 2.0 để P3 nếu có nhu cầu |

`identity_provider(id, type 'ldap'|'oidc', name, enabled, config jsonb, secret_ref)`, ví dụ:

```jsonc
// ldap
{ "url": "ldaps://ad.corp.local:636", "bindDn": "cn=svc-opendbapi,ou=svc,dc=corp,dc=local",
  "userSearchBase": "ou=users,dc=corp,dc=local", "userSearchFilter": "(sAMAccountName={0})",
  "groupSearchBase": "ou=groups,dc=corp,dc=local", "groupSearchFilter": "(member={0})",
  "attributes": { "email": "mail", "displayName": "displayName" } }
// oidc
{ "issuerUri": "https://sso.corp.local/realms/main", "clientId": "opendbapi",
  "scopes": ["openid", "profile", "email"], "groupsClaim": "groups", "usernameClaim": "preferred_username" }
```

**Cấp tài khoản và đồng bộ role:**

- User LDAP/OIDC được tạo tự động ở lần đăng nhập đầu tiên (`source`, `idp_id`, `external_id`), không có mật khẩu cục bộ.
- `group_role_mapping(idp_id, external_group, workspace_id, role)`: ở **mỗi lần đăng nhập**, role của user trong các workspace được tính lại từ nhóm hiện tại. Bị rút khỏi nhóm thì mất quyền ở lần đăng nhập sau. Directory không báo thay đổi cho OpenDBAPI, nên một session đang mở (tối đa 8 giờ không hoạt động) giữ quyền cũ cho tới lần đăng nhập kế tiếp; admin có thể vô hiệu hoá user để cắt ngay.
- Role được gán tay cho user LDAP/OIDC vẫn giữ, trừ khi mapping của workspace đó đặt `managed = true` (khi đó chỉ mapping quyết định).
- Mapping có thể cấp `system_admin` (vd nhóm `opendbapi-admins`).
- Mọi lần đăng nhập, đăng nhập thất bại, và thay đổi role đều ghi `audit_log`.

---

## 9. Pipeline xử lý request

### 9.1 Các bước

```text
 1. Trace/AccessLog filter     sinh requestId, mở span OTel
 2. Firewall                   IP / CIDR                                      → 403 ip_blocked
 3. Resolve                    (method, path) → CompiledEndpoint; không có     → 404 not_found
 4. Authenticate               JWT (+ HMAC) nếu private                       → 401
 5. Authorize                  endpoint_grant                                  → 403 not_granted
 6. Rate limit                 theo endpoint và theo service account           → 429
 7. Bind params                ép kiểu, validate                               → 400 invalid_param
 8. Rule tham số                                                                → 400 rule_failed
 9. Cache lookup               khoá có tính tới phạm vi client (§9.5)
10. Render                     template | script → BoundSql
11. Rewrite                    row filter → phân trang
12. Execute                    timeout, maxRows, stream hoặc nạp vào bộ nhớ
13. Post-process               masking → map kết quả
14. Envelope                   { data, meta }
15. Cache put, mã hoá response
16. Ghi access log (bất đồng bộ), metric; nếu lỗi thì gửi cảnh báo webhook (§13.2)
```

### 9.2 Map kết quả (P2)

- `naming: camelCase` đổi `created_at` thành `createdAt`.
- `types`: `decimal_string` (tránh mất chính xác ở JS), `iso8601`, `epoch_ms`, `boolean` (ép 0/1).
- `nest`: gộp các dòng phẳng thành object lồng nhau:

```jsonc
"nest": [
  { "key": "id",                         // gom các dòng theo cột này
    "collections": [
      { "as": "orders", "prefix": "order_", "key": "order_id" }
    ] }
]
```

Các dòng `id, name, order_id, order_total` trở thành `{id, name, orders: [{id, total}]}`.

- `shape: single`: trả object đầu tiên, không có dòng nào thì trả 404 `not_found`. `shape: scalar`: trả giá trị của cột đầu tiên.

### 9.3 Phân trang (P1)

- Bật bằng `pagination.enabled`. Bên gọi gửi `page` (bắt đầu từ 1), `size`, và tuỳ chọn `sort` nếu template có `<orderBy>`.
- Engine (`opendbapi-sql/page/Paginator`) xử lý câu SQL đã render:
  - data: nếu tầng ngoài cùng của câu chưa tự giới hạn (không có LIMIT/OFFSET/FETCH/TOP) thì **nối** mệnh đề phân trang; ngược lại, hoặc khi không parse được, thì **bọc** `SELECT * FROM (<sql>) page_q`. Cách bọc luôn đúng. Theo dialect: `LIMIT ? OFFSET ?` (PostgreSQL, MySQL, MariaDB, SQLite, H2, ClickHouse, không rõ); `OFFSET ? ROWS FETCH NEXT ? ROWS ONLY` (Oracle, DB2); SQL Server thêm `ORDER BY (SELECT NULL)` khi câu chưa có ORDER BY, vì OFFSET bắt buộc đi sau ORDER BY.
  - count: `SELECT COUNT(*) FROM (<sql>) count_q`. Bỏ ORDER BY ở tầng ngoài nếu an toàn: câu không tự giới hạn và ORDER BY không chứa tham số. Lý do: thứ tự không đổi kết quả đếm, và SQL Server không cho ORDER BY trong derived table. Có thể tắt count bằng `pagination.count = false` để nhanh hơn; khi đó trả `hasNext` thay cho `total`.
  - Dialect suy ra từ JDBC URL.
- `mode: keyset` (P2): `after=<cursor>`, với cursor được mã hoá từ giá trị của các cột sắp xếp.

### 9.4 Envelope

```jsonc
{
  "data": [ … ],
  "meta": {
    "version": 7,                 // phiên bản đã phục vụ request này [E]
    "cached": false,              // [E]
    "page": 1, "size": 20, "total": 134, "hasNext": true,
    "requestId": "01JD…"
  }
}
```

Lỗi trả về theo chuẩn RFC 9457 (`application/problem+json`):

```json
{ "type": "about:blank", "title": "Not granted", "status": 403,
  "code": "not_granted", "detail": "Service account 'reporting' chưa được cấp quyền gọi endpoint này.",
  "requestId": "01JD…" }
```

HTTP status phản ánh đúng lỗi (400/401/403/404/409/429/500/504), không trả 200 cho mọi trường hợp như DBAPI.

### 9.5 Cache

Cache dùng khoá `(endpointId, version, paramsHash, scope)`. `scope = client` sẽ đưa thêm service account vào khoá. **Endpoint có `data_policy` theo client bắt buộc dùng `scope = client`**, kiểm tra khi publish. Lý do: tránh trường hợp client này đọc được dữ liệu đã lọc hoặc che cho client khác [E].

### 9.6 Trả kết quả dạng stream (P1)

- Bên gọi chọn định dạng bằng `Accept: application/x-ndjson` hoặc `text/csv`, hoặc tham số `?format=ndjson|csv`. Endpoint phải khai báo `streaming.allowed`.
- Engine dùng `ResultSet` forward-only với `fetchSize` (ví dụ 1.000), ghi thẳng ra output stream. Chạy trên virtual thread nên không giữ platform thread.
- Ở chế độ stream, các tính năng sau bị tắt: `nest`, cache. Validate sẽ báo nếu cấu hình mâu thuẫn.
- `maxRows` vẫn áp dụng. Riêng với stream có thể đặt giới hạn cao hơn qua `limits.streamMaxRows` (tối đa 10.000.000).
- Stream bỏ qua phân trang. Status và header chỉ được gửi sau khi câu SQL đã chạy, nên lỗi xảy ra trước dòng đầu tiên vẫn trả problem response như bình thường. Lỗi xảy ra giữa chừng: NDJSON ghi thêm một dòng cuối `{"_meta":{"error":"query_failed","requestId":...}}`; CSV dừng lại, nguyên nhân nằm trong log. Khi bị cắt ở giới hạn số dòng, NDJSON ghi dòng cuối `{"_meta":{"truncated":true,...}}`.
- Định dạng không được phép trả 406 `not_acceptable`; giá trị `format` không hợp lệ trả 400 `invalid_param`.
- Đo trên PostgreSQL dev (2026-11-17): 1 triệu dòng NDJSON chạy hết khoảng 6 giây, CSV khoảng 5 giây, JVM giới hạn `-Xmx128m` vẫn chạy được, live heap sau GC không đổi.

---

## 10. OpenAPI tự sinh (P1)

- `GET /{context}/{ws}/openapi.json`: tài liệu OpenAPI 3.1 của một workspace, gồm các endpoint live mà token hiện tại được phép gọi (endpoint public luôn có mặt). Path của endpoint không chứa dấu chấm nên không trùng với route này.
- `GET /admin/api/ws/{ws}/openapi.json` (mọi endpoint live, kể cả private) và `GET /admin/api/ws/{ws}/endpoints/{id}/openapi.json`: thành viên workspace tải về từ UI [E].
- Nguồn dữ liệu cho tài liệu:
  - Tham số: lấy từ `definition.params` (gồm cả `enum`, `min`/`max`, `pattern`).
  - Request body: lấy JSON Schema trong `definition.body`.
  - Response: lấy từ `output_schema` đã chốt khi publish, sau khi áp dụng map kết quả và che dữ liệu, kèm envelope và lỗi RFC 9457.
  - Security scheme: OAuth2 `clientCredentials` (tokenUrl `/oauth2/token`).
- Swagger UI tại `/{context}/{ws}/docs.html`, phục vụ từ webjar trong jar (chạy được khi không có internet), tắt bằng `opendbapi.serving.docs-ui=false`.
- Kiểu của cột trả về lấy từ metadata mà driver báo lúc validate; nếu DB không mô tả được câu SQL, validate báo cảnh báo `describe_failed` và tài liệu không có danh sách cột.

---

## 11. Viết và kiểm thử endpoint

### 11.1 Preview [E]

`POST /admin/api/endpoints/{id}/preview`, body `{ definition (bản draft đang hiển thị trên màn hình), params }`:

- Render bằng **đúng** hàm render của serving, chạy với giới hạn 100 dòng và timeout 10 giây.
- Trả `{ sql, binds, columns, rows, warnings, durationMs }`. Lỗi của DB được hiển thị nguyên văn.
- Không ghi cache, không tính rate limit, không ghi access log.

### 11.2 Try-it [E]

`POST /admin/api/ws/{ws}/endpoints/{id}/try`, body `{ serviceAccountId, params, page, size }`:

- Server tạo một token thật, sống 60 giây, cho service account đó (claim `cid = tryit:<userId>`), rồi **gọi chính pipeline serving trong cùng tiến trình** bằng một request HTTP loopback (cùng filter chain và handler). Không viết một đường xử lý "tương đương" riêng. Request mang header nội bộ `X-OpenDBAPI-Try-It`, ký bằng bí mật ngẫu nhiên của tiến trình, cho biết người bấm và IP thật của họ; firewall và rate limit áp dụng theo IP đó. Token `tryit:` không dùng được nếu thiếu header này.
- Không bỏ qua bước kiểm tra nào: chưa cấp quyền thì nhận 403 `not_granted` thật, và UI hiển thị link sang tab Access để sửa.
- Request Try-it có tính vào rate limit và có ghi access log với `actor_user_id` (được lọc khỏi thống kê). Ghi thêm một dòng `audit_log`.
- Token không bao giờ được gửi xuống trình duyệt. UI hiển thị `Authorization: Bearer ••••` và một khối `curl` dùng biến `$TOKEN`.
- Quyền cần có: role `author` trở lên. Chỉ gọi được bản đang live.

### 11.3 Bộ test case cho từng endpoint (P2)

```jsonc
// endpoint_test_case
{
  "name": "lọc theo status",
  "serviceAccountId": null,           // null = chỉ chạy pipeline render + execute (như preview)
  "params": { "status": "active", "page": 1 },
  "expect": {
    "status": 200,
    "rowCount": { "min": 1 },
    "jsonPath": [ { "path": "$.data[0].status", "equals": "active" } ],
    "schemaMatchesOutput": true,
    "maxDurationMs": 500
  }
}
```

- Chạy từ UI (tab Tests), qua `POST /admin/api/endpoints/{id}/tests:run`, hoặc bằng CLI `opendbapi test --env staging` (trả exit code ≠ 0 nếu có test thất bại, dùng trong CI).
- Tuỳ chọn `publish.requireTestsPass = true`: chỉ cho publish khi mọi test case đều đạt trên bản draft.

---

## 12. Bảo mật dữ liệu: che cột và lọc dòng (P2)

### 12.1 Che dữ liệu (masking)

Gắn theo datasource + cột (áp dụng cho mọi endpoint) hoặc theo từng endpoint:

| Kiểu | Ví dụ kết quả |
|---|---|
| `phone` | `090****123` |
| `email` | `n***@example.com` |
| `partial(keepStart, keepEnd)` | |
| `hash` | SHA-256 có salt |
| `null` | luôn trả `null` |

- Áp dụng sau khi thực thi, theo **tên cột trong kết quả**. Engine dùng JSqlParser để truy ngược alias về cột gốc. Không truy ngược được thì cảnh báo khi validate.
- Ngoại lệ theo service account: `rule.exemptServiceAccounts`.

### 12.2 Lọc dòng (row filter)

```jsonc
{ "kind": "row_filter", "endpointId": "…",
  "rule": { "column": "tenant_id", "claim": "tenant" } }
```

- Giá trị lấy từ thuộc tính của service account (`service_account.attributes.tenant`), không lấy từ tham số request.
- Engine bọc câu SQL: `select * from (<sql>) t where t.tenant_id = ?`. Cột phải xuất hiện trong kết quả, nếu không thì báo lỗi validate.
- Endpoint có row filter bắt buộc dùng cache `scope = client` (§9.5).

---

## 13. Rule tham số và cảnh báo lỗi (P2)

OpenDBAPI không có cơ chế plugin: không có SPI, không nạp jar từ thư mục `plugins/`. Code chạy trong cùng JVM sẽ có toàn quyền, kể cả đọc master key và mật khẩu datasource, và một SPI công khai buộc phải giữ ổn định chữ ký trong khi pipeline vẫn đang thay đổi. Các nhu cầu mà plugin của DBAPI phục vụ được làm thành tính năng có sẵn:

| Nhu cầu | Tính năng |
|---|---|
| Cache kết quả | Cache Caffeine có sẵn, cấu hình ở `cache` của endpoint (§9.5) |
| Biến đổi kết quả | Map kết quả (§9.2), task Groovy (§7.4) |
| Xử lý, kiểm tra tham số | Kiểu và giá trị mặc định của tham số (§5.2), rule tham số (§13.1) |
| Cảnh báo khi API lỗi | Kênh cảnh báo webhook (§13.2) |

Cần thêm hành vi mới thì viết thẳng vào tính năng tương ứng.

### 13.1 Rule tham số

Rule viết bằng biểu thức SpEL (an toàn hơn Groovy, đủ dùng cho validate), ví dụ `#params['to'] >= #params['from']`, kèm `message`. SpEL chạy với `SimpleEvaluationContext`, không gọi được kiểu, bean hay phương thức tuỳ ý. Rule sai trả **400** `rule_failed` kèm `message`.

### 13.2 Cảnh báo lỗi

Workspace admin khai báo kênh cảnh báo dạng webhook (Slack, Microsoft Teams, Google Chat, hoặc webhook JSON chung). Endpoint bật `alarm.enabled` thì khi request lỗi 5xx hoặc timeout, hệ thống gửi cảnh báo bất đồng bộ tới các kênh của workspace. Cảnh báo chứa tên endpoint, mã lỗi, request id, thời điểm; không chứa giá trị tham số hay dữ liệu trả về. Cảnh báo trùng (cùng endpoint, cùng mã lỗi) bị gộp trong một khoảng thời gian cấu hình được, mặc định 5 phút. URL webhook được coi là bí mật và lưu niêm phong bằng master key.

---

## 14. Rate limit, firewall

- Token bucket (Bucket4j): theo endpoint, theo service account, theo IP cho endpoint public. Vượt giới hạn trả 429 kèm header `Retry-After` và `RateLimit-*`.
- Firewall hỗ trợ IP đơn và CIDR, ở chế độ whitelist hoặc blacklist. Header `X-Forwarded-For` chỉ được tin khi request đến từ proxy nằm trong `trusted-proxies`.

---

## 15. Cấu hình dưới dạng code, môi trường, import

### 15.1 Định dạng YAML (P3; export từ P2)

```yaml
apiVersion: opendbapi/v1
kind: Endpoint
metadata:
  name: get-user
  group: users
spec:
  method: GET
  path: v1/users/{id}
  access: private
  datasource: main-db            # tên logic, gắn với datasource thật theo môi trường
  params:
    - { name: id, in: path, type: bigint, required: true }
  task:
    type: sql
    template: |
      select id, name, email, created_at from users where id = #{id}
  output: { shape: single, naming: camelCase }
  tests:
    - name: user tồn tại
      params: { id: 1 }
      expect: { status: 200 }
```

Các `kind` khác: `Datasource` (không chứa bí mật, chỉ `secretRef`), `Group`, `ServiceAccount`, `Grant`, `DataPolicy`.

### 15.2 Môi trường

- Bảng `environment(name, datasource_bindings jsonb)` ánh xạ tên logic (`main-db`) sang datasource thật của từng môi trường.
- CLI:
  - `opendbapi export --dir ./api`: ghi YAML ra thư mục.
  - `opendbapi plan --env prod`: so sánh khác biệt, giống `terraform plan`.
  - `opendbapi apply --env prod [--publish]`: áp dụng thay đổi, chạy test case trước khi publish.
- Đường đi đề xuất: viết trên dev → export vào Git → PR review → CI chạy `test` + `plan` → merge → `apply` lên staging/prod.
- Môi trường prod có thể bật **chế độ chỉ đọc cho UI** (`opendbapi.ui.readOnly = true`): mọi thay đổi phải đi qua Git.

### 15.3 Import từ DBAPI (P2)

Nhận file export JSON của DBAPI (API, nhóm, datasource), ánh xạ theo §4. Các điểm cần xử lý:

- `${}` trong template bị đánh dấu là cần sửa tay.
- Endpoint được nhập ở trạng thái `draft`.
- Client của DBAPI không được nhập; phải tạo lại service account.

---

## 16. Quản lý phiên bản API (P3)

- **Minor** (không phá vỡ bên gọi): publish phiên bản mới của cùng endpoint. `meta.version` cho biết phiên bản nào đã phục vụ request.
- **Major** (phá vỡ bên gọi): action "Tạo major mới" nhân bản endpoint sang path mới (`v1/…` → `v2/…`), ở trạng thái draft, gắn `lineage_id` chung. Hai bản chạy song song; bản cũ có thể được đánh dấu `deprecated` (thêm header `Deprecation` và `Sunset` vào response).
- Validate khi publish so sánh `output_schema` mới với bản đang live. Nếu bỏ cột hoặc đổi kiểu thì cảnh báo "breaking change" và gợi ý tạo major mới.

---

## 17. Giao diện người dùng

UI **chỉ phục vụ tính năng DBAPI** và được thiết kế mới cho gọn. Từ Egret chỉ lấy **khung**: stack, app shell, layout, các primitive dùng chung và quy tắc trình bày. Không mang theo các khu vực, tính năng hay phụ thuộc khác của Egret.

### 17.1 Lấy gì từ Egret

| Lấy (copy rồi chỉnh) | Nguồn trong [`egret-data/platform`](https://github.com/egret-data/platform) `frontend/src` |
|---|---|
| Cấu hình build: Vite, TypeScript, Tailwind v4 + token (`index.css`), ESLint (gồm jsx-a11y), Vitest, Playwright, Orval | thư mục gốc `frontend/` |
| App shell: `AppShell`, `Sidebar`, `Header`, `WorkspaceSwitcher`, `PageContainer`, `PageTitle`, `DetailHeader`, `DetailFact`, `StatePage`, `LazyRouteArea` | `components/layout/` |
| Primitive UI (Radix): button, input, textarea, select, checkbox, switch, radio-group, label, dialog, alert-dialog, confirm-delete-dialog, dropdown-menu, popover, tooltip, tabs, table, badge, status-badge, skeleton, sonner, scroll-area, separator, entity-link, icon-button, date-time-picker | `components/ui/` |
| Trang danh sách: `ListPageShell`, `EmptyState`, `SummaryStrip`, `RowActionsMenu` | `components/explore/` |
| Bảng setting: `ConfigTable` | `components/config/` |
| Monaco: `MonacoEditor` (nạp lazy) | `components/monaco/` |
| Auth: `AuthProvider`, `useAuth`, `guards`, `LoginPage` (sửa thêm LDAP/SSO) | `auth/` |
| Tiện ích: `mutator.ts`/`client.ts` (fetch + CSRF + lỗi), `queryClient.ts`, `useUnsavedChangesWarning`, `relativeTime`, `downloadTextFile`, theme provider, `useIsDark` | `api/`, `lib/`, `hooks/` |
| Quy tắc: `docs/en/frontend-rules.md` (bỏ các quy tắc của tính năng không dùng: chart palette, folder, bookmark, share) | `docs/` |

**Không lấy:** explore/chart/dashboard/sqllab, workflows, governance, policy/Ranger, control plane, apps/kernel, sharing, bookmark, folder kéo-thả, bulk select, impersonation, feature flag theo workspace, xterm, plotly, react-grid-layout, dagre/xyflow (cân nhắc lại khi làm workflow DAG ở P3).

Giữ NOTICE Apache-2.0 khi copy. Sau khi copy, **component chung được sở hữu tại repo này**, không đồng bộ ngược với Egret.

### 17.2 Bố cục

```text
┌──────────────────────────────────────────────────────────────────────────┐
│ ◧ OpenDBAPI   [sales ▾ workspace]                     ☾  ?  [VN ▾ user] │  Header
├──────────────┬───────────────────────────────────────────────────────────┤
│ Endpoints    │                                                           │
│ Datasources  │   PageContainer                                           │
│ Clients      │                                                           │
│ Activity     │                                                           │
│ ──────────── │                                                           │
│ Settings     │                                                           │
│ ──────────── │                                                           │
│ Admin ⚙      │  (chỉ hiện với system admin)                              │
└──────────────┴───────────────────────────────────────────────────────────┘
```

- Sidebar có 5 mục cho workspace, cộng 1 mục Admin cho system admin. Màn hình nhỏ thì sidebar thu thành thanh icon.
- `WorkspaceSwitcher` chỉ liệt kê workspace mà user là thành viên. Đổi workspace thì giữ nguyên khu vực đang mở (vd đang ở Endpoints thì sang Endpoints của workspace mới).
- User chưa thuộc workspace nào sẽ thấy trang "Chưa có workspace" (giống `NoWorkspaceYet` của Egret), kèm hướng dẫn liên hệ admin.

### 17.3 Route

```text
/login                                      chọn nguồn: nội bộ | LDAP | SSO
/w/:ws/endpoints                            danh sách
/w/:ws/endpoints/new                        trang tạo (tên, method, path, datasource, kiểu task)
/w/:ws/endpoints/:id                        chi tiết
    (index)   Query      editor + params + preview
    settings  Settings   method/path, access, phân trang, output, cache, giới hạn, stream
    access    Access     service account được cấp quyền, data policy
    try       Try it
    tests     Tests      (P2)
    versions  Versions
/w/:ws/datasources                          danh sách
/w/:ws/datasources/new | /:id               tạo / chi tiết (Settings, Tables, Usage)
/w/:ws/clients                              service account (mỗi dòng mở ra danh sách client)
/w/:ws/clients/new                          tạo service account
/w/:ws/clients/:accountId/new-client        tạo client, secret hiện một lần trên chính trang này
/w/:ws/activity                             thống kê + bảng log
/w/:ws/settings                             General | Members | Security (firewall, rate limit) | Environments (P3)
/admin/workspaces | /admin/users | /admin/identity-providers | /admin/audit
```

Tab chi tiết của endpoint giảm từ 7 của Egret xuống 5 ở P1. Phần **Response** được gộp vào Settings (nhóm "Output") và vào tab Query (xem trước schema). Mỗi tab là một route con nên còn nguyên sau khi tải lại trang.

### 17.4 Màn hình

**Endpoints (danh sách).** Dùng `ListPageShell`. Toolbar gồm ô tìm kiếm và bộ lọc nhóm/datasource. `SummaryStrip` hiển thị `Live · Draft · Disabled · Trash` và vừa đóng vai bộ lọc. Cột: tên (`EntityLink`) · `METHOD /path` (mono) · trạng thái (`StatusBadge`, thêm chấm "chưa publish" khi `dirty`) · v{N} · datasource · sửa lần cuối. `RowActionsMenu`: Open, Duplicate, Copy URL, Download OpenAPI, Disable/Enable, Move to trash. Nhóm hiển thị bằng một cột và một bộ lọc, không có cây thư mục kéo-thả.

**Endpoint: Query.** Ngoại lệ của quy tắc bảng setting:

```text
DetailHeader:  Get user by id   [Live v7] [● unpublished changes]     [Save] [Publish ▾]
               GET /api/sales/v1/users/{id}  ⧉                                   ⋯
Tabs: Query | Settings | Access | Try it | Versions
┌ Datasource [main-db ▾]   Language [SQL template ▾ | Groovy]                       ┐
├───────────────────────────────────────────────┬───────────────────────────────────┤
│ MonacoEditor                                  │ Parameters                        │
│                                               │ name   type    in     req         │
│                                               │ id     bigint  path   ✓     ⋯     │
│                                               │ [+ Add parameter] [Detect from SQL]│
├───────────────────────────────────────────────┴───────────────────────────────────┤
│ Preview   id = [ 1 ]   [Run ▶]                    120 ms · 1 row · 2 warnings      │
│ Result | SQL & binds | Warnings | Output schema                                   │
└───────────────────────────────────────────────────────────────────────────────────┘
```

- "Detect from SQL" quét `#{x}` trong template và gợi ý danh sách tham số.
- Save / Preview / Publish có quan hệ khác nhau với `dirty` (giữ quyết định của Egret): Preview chạy trên nội dung đang hiển thị; Publish bị vô hiệu kèm lý do "Save changes first" khi chưa lưu. Publish mở dialog nhỏ gồm ghi chú phiên bản, kết quả validate và cảnh báo breaking change (§16).
- Rời trang khi còn thay đổi chưa lưu thì được cảnh báo (`useUnsavedChangesWarning`).

**Endpoint: Settings / Access.** Mỗi tab là một `ConfigTable` duy nhất `Setting | Details | Value`, chia nhóm bằng header row: *Endpoint* (name, method, path, access, group), *Pagination*, *Output* (naming, shape, types, nest), *Cache*, *Limits*, *Streaming*. Tab Access gồm hai bảng: service account được cấp quyền (thêm/bỏ), và data policy áp lên endpoint.

**Endpoint: Try it.** Chọn service account (picker), nhập tham số, bấm Send. Bên phải hiển thị request (`Authorization: Bearer ••••`), status, header, body JSON (Monaco chỉ đọc), thời gian chạy, và khối `curl` có nút copy. Lỗi 403 `not_granted` kèm link sang tab Access.

**Endpoint: Versions.** Bảng: v · người publish · thời điểm · ghi chú · nhãn "live". Có thể chọn hai phiên bản để xem diff (Monaco diff editor). Nút Rollback có xác nhận.

**Datasources.** Danh sách: tên · loại/dialect · host · số endpoint đang dùng · trạng thái kết nối lần kiểm tra gần nhất. Trang tạo/chi tiết là `ConfigTable` gồm nhóm *Connection* (dialect picker tự điền driver và mẫu URL), *Credentials* (chọn kiểu secret: nhập vào để mã hoá / `env:` / `vault:`), *Pool*, *Safety* (`read_only`). Nút Test connection hiển thị kết quả ngay bên dưới. Tab Tables duyệt bảng và cột (dùng để chèn vào editor). Tab Usage liệt kê endpoint đang dùng datasource.

**Clients.** Bảng service account: tên · số client · số quyền · lần dùng gần nhất · trạng thái. Mở rộng một dòng để thấy các client: client id · tạo lúc · hết hạn · lần dùng gần nhất · Revoke / Rotate secret. Trang tạo client hiển thị secret **một lần**, không đóng được bằng click ra ngoài hay Escape (giữ quyết định của Egret).

**Activity.** Bộ chọn khoảng thời gian, 4 ô số liệu (request · tỷ lệ lỗi · p95 latency · client hoạt động), biểu đồ request theo ngày, top endpoint, top lỗi. Bên dưới là bảng log có lọc theo endpoint/client/status. Mặc định ẩn các lời gọi từ Try-it, có toggle để hiện.

**Settings (workspace).** *General* (tên, slug chỉ đọc, hạn mức), *Members* (thêm user, đổi role, xem role đến từ mapping LDAP/SSO và khoá sửa các role đó), *Security* (firewall, rate limit mặc định).

**Admin (hệ thống).** Workspaces (tạo, hạn mức, archive), Users (khoá, đặt lại mật khẩu cho user nội bộ, đặt system admin), Identity providers (form LDAP/OIDC, nút Test bind / Test login, bảng group → role mapping), Audit log.

**Login.** Form username/password có radio chọn "Local / LDAP" (chỉ hiện các nguồn đang bật), cùng các nút "Sign in with {SSO name}". Lỗi đăng nhập không tiết lộ username có tồn tại hay không.

### 17.5 Quy tắc giữ lại từ Egret

| Quy tắc | Áp dụng |
|---|---|
| FE-PAGE | `PageContainer` + `PageTitle`; danh sách dùng `ListPageShell`; tạo đối tượng lớn bằng trang riêng, đối tượng nhỏ (nhóm, thành viên) bằng dialog; setting dùng bảng `Setting \| Details \| Value`; chi tiết dùng `DetailHeader`; tab ở trong URL |
| FE-STATE | Thứ tự: lỗi → đang tải (skeleton) → rỗng. Bộ lọc không có kết quả thì nói rõ là do lọc. Không có quyền thì hiện trang `Forbidden` bên trong shell |
| FE-ACTION | Xác nhận trước khi xoá/disable/revoke; thông báo lỗi lấy nguyên từ server (`detail` của RFC 9457) và không xoá dữ liệu đã nhập; nút bị vô hiệu có tooltip nói lý do; cảnh báo khi rời trang có thay đổi chưa lưu |
| FE-VISUAL | Màu lấy từ token, sáng/tối đều đúng, chữ số dạng tabular, icon lucide, trạng thái dùng `StatusBadge` |
| FE-FORM | State form viết tay, `Label` gắn với input, picker cho giá trị đã biết, lỗi nằm cạnh trường |
| FE-DATA | Hook sinh bằng Orval, mỗi domain một key factory, `localStorage` chỉ lưu tuỳ chọn hiển thị |
| FE-A11Y | jsx-a11y, dùng được bằng bàn phím, không truyền đạt thông tin chỉ bằng màu, tôn trọng reduced motion |
| FE-COPY | Tiếng Anh, sentence case. i18n tiếng Việt ở P2 (`react-i18next`, file `en.json`/`vi.json`) |

### 17.6 Kiểm chứng UI

Gate: `npm run lint`, `tsc -b`, `vitest run`, `vite build`, kiểm tra Monaco được nạp lazy. Playwright chạy luồng chính (§24). Mọi thay đổi có hiển thị phải xem trong trình duyệt ở cả chế độ sáng và tối.

---

## 18. Quan sát hệ thống (P1)

- `/actuator/health`, `/actuator/prometheus`.
- Metric:
  - `opendbapi_requests_total{endpoint,version,status,client}`
  - `opendbapi_request_duration_seconds` (histogram)
  - `opendbapi_db_duration_seconds{datasource}`
  - `opendbapi_pool_active{datasource}`
  - `opendbapi_cache_hits_total`
  - `opendbapi_access_log_dropped_total`
- Tracing OpenTelemetry: span cho pipeline, render, thực thi DB (gắn `db.system`, không gắn giá trị tham số) và HTTP upstream. `trace_id` được ghi vào access log.
- **Log query chậm:** request có thời gian DB vượt `slowQueryMs` (mặc định 1.000) thì ghi SQL đã render, kèm tên tham số nhưng không kèm giá trị.
- Hiện trạng P1: chưa có `opendbapi_cache_hits_total` (cache thuộc P2). Span được export qua OTLP khi đặt `management.otlp.tracing.endpoint`; tỷ lệ lấy mẫu đặt bằng `OPENDBAPI_TRACE_SAMPLING` (mặc định 0,1). Logger của log query chậm là `opendbapi.slow`.

---

## 19. Access log

- Writer: `file` (JSON lines), `db` (batch bất đồng bộ, hàng đợi giới hạn), `kafka`. P1 có `file` (logger `opendbapi.access`) và `db` (hàng đợi 10.000 bản ghi, ghi theo lô mỗi giây; khi đầy thì bỏ bản ghi và tăng `opendbapi_access_log_dropped_total`). Có cột `request_id` để tra lời gọi lỗi từ request id mà bên gọi gửi lại.
- Tự dọn log theo `retentionDays` (mặc định 30; `opendbapi.access-log.retention-days`), chạy mỗi 6 giờ.
- Có thể dùng ClickHouse cho khối lượng lớn.

---

## 20. Xuất API thành tool MCP (P3)

- Endpoint `/mcp` (Streamable HTTP). Xác thực bằng chính OAuth2 token của service account.
- Mỗi endpoint live mà service account được phép gọi, và có bật `mcp.exposed = true`, trở thành một tool:
  - `name`: tên endpoint dạng slug.
  - `description`: mô tả của endpoint.
  - `inputSchema`: lấy từ params.
- Gọi tool chạy qua đúng pipeline serving (quyền, rate limit, che dữ liệu, access log).
- Kết quả bị cắt theo `mcp.maxRows` (mặc định 200), kèm `meta.truncated`.

---

## 21. Admin REST API

Viết dạng resource RESTful, có tài liệu OpenAPI (springdoc), và frontend sinh hook bằng Orval. Body request có `deny-unknown-fields`. Lỗi trả theo RFC 9457.

### 21.1 Cấp hệ thống: `/admin/api`

| Resource | Method & path | Quyền |
|---|---|---|
| Đăng nhập | `GET /auth/providers` (nguồn đang bật, cho màn hình login), `POST /auth/login` (`{source: local\|ldap, idpId?, username, password}`), `GET /auth/oidc/{idpId}` (chuyển hướng sang IdP, có PKCE), callback `/login/oauth2/code/{idpId}`; SSO lỗi thì về `/login?error=sso&reason=…`, `POST /auth/logout` | công khai |
| Phiên | `GET /me` (user, system_admin, danh sách workspace + role) | đã đăng nhập |
| Đổi mật khẩu | `POST /me/password` (chỉ user nội bộ) | đã đăng nhập |
| Workspace | `GET/POST /workspaces`, `GET/PATCH /workspaces/{ws}`, `POST /workspaces/{ws}:archive`, `:restore` | system admin (`GET` lọc theo thành viên) |
| User | `GET/POST /users` (tạo user nội bộ), `GET/PATCH /users/{id}`, `POST /users/{id}:disable`, `:enable`, `:reset-password` | system admin |
| Identity provider | `GET/POST /identity-providers`, `GET/PATCH/DELETE /identity-providers/{id}`, `POST /identity-providers/{id}:test` (LDAP: bind + tìm user mẫu; OIDC: lấy discovery document), `GET/PUT /identity-providers/{id}/group-mappings` | system admin |
| Audit hệ thống | `GET /audit` | system admin |
| Hệ thống | `GET /system/info` (phiên bản, chế độ chạy, base URL) | đã đăng nhập |

### 21.2 Cấp workspace: `/admin/api/ws/{ws}`

Quyền tối thiểu theo bảng §8.4.

| Resource | Method & path |
|---|---|
| Thành viên | `GET/POST /members`, `PATCH/DELETE /members/{userId}`, `GET /user-search?q=` (tìm user để thêm); role đến từ mapping thì trả 409 `managed_by_directory`, owner cuối cùng thì 409 `last_owner` |
| Audit | `GET /audit` (admin workspace) |
| Nhóm (thư mục) | `GET/POST /groups`, `PATCH/DELETE /groups/{id}` |
| Datasource | `GET/POST /datasources`, `GET /datasources/dialects`, `GET/PATCH/DELETE /datasources/{id}` (xoá bị từ chối khi endpoint đang dùng), `POST /datasources/{id}:test` (author), `POST /datasources/test` (cấu hình chưa lưu, `existingId` để dùng lại mật khẩu đã lưu; admin), `GET /datasources/{id}/schemas`, `GET /datasources/{id}/tables?schema=`, `GET /datasources/{id}/columns?schema=&table=` (author). Hạn mức `quotas.datasources` của workspace được kiểm tra khi tạo |
| Endpoint | `GET/POST /endpoints` (lọc `state`, `groupId`, `datasourceId`, `q`, `trashed`), `GET/PATCH /endpoints/{id}`, `POST /endpoints/{id}:validate`, `:publish`, `:disable`, `:enable`, `:trash`, `:restore`, `:duplicate`, `:new-major`, `DELETE /endpoints/{id}` (chỉ khi đã ở thùng rác) |
| Phiên bản | `GET /endpoints/{id}/versions`, `GET /endpoints/{id}/versions/{v}`, `POST /endpoints/{id}/versions/{v}:rollback`, `GET /endpoints/{id}/versions/{a}/diff/{b}` |
| Viết & kiểm thử | `POST /endpoints/{id}/preview`, `POST /endpoints/{id}/try`, `GET/POST /endpoints/{id}/tests`, `PATCH/DELETE /endpoints/{id}/tests/{tid}`, `POST /endpoints/{id}/tests:run` |
| Tài liệu | `GET /endpoints/{id}/openapi` |
| Service account | `GET/POST /service-accounts`, `PATCH/DELETE /service-accounts/{id}`, `PUT /service-accounts/{id}/grants` |
| Client | `GET/POST /service-accounts/{id}/clients`, `POST /clients/{cid}:revoke`, `POST /clients/{cid}:rotate-secret` |
| Data policy | `GET/POST /data-policies`, `PATCH/DELETE /data-policies/{id}` |
| Cảnh báo | `GET/POST /alert-channels`, `PATCH/DELETE /alert-channels/{id}`, `POST /alert-channels/{id}:test` |
| Rate limit | `GET/PUT /rate-limits` |
| Firewall | `GET/PUT /firewall` |
| Activity | `GET /activity/logs`, `GET /activity/stats?metric=daily|success-ratio|top-endpoints|top-clients|top-latency|top-ips&from&to` |
| Audit workspace | `GET /audit` |
| Import/Export | `POST /import/dbapi`, `GET /export?format=yaml`, `POST /import?format=yaml&mode=plan|apply` |
| Môi trường | `GET/POST /environments`, `PATCH /environments/{name}` |
| Hệ thống | `GET /system/info` (phiên bản, chế độ chạy, base URL) |

---

## 22. Cấu hình (`application.yml`)

```yaml
opendbapi:
  mode: standalone                       # standalone | cluster
  environment: dev
  master-key: ${OPENDBAPI_MASTER_KEY}    # mã hoá secret_ref dạng enc:
  serving:
    context: api
    url-base: ""
    docs-ui: true
  oauth2:
    issuer: ${OPENDBAPI_ISSUER:http://localhost:8520}
    access-token-ttl: 15m
    signing-key-path: ${OPENDBAPI_SIGNING_KEY:./data/jwk.json}
  sql:
    default-timeout: 30s
    default-max-rows: 10000
    slow-query: 1s
  script:
    groovy-enabled: true
    timeout: 2s
  publish:
    require-tests-pass: false
  access-log:
    writer: file                         # file | db | kafka
    retention-days: 30
  ui:
    read-only: false
  trusted-proxies: []
  auth:
    local:
      enabled: true
      lockout-attempts: 5
    session-idle-timeout: 8h
    # LDAP/OIDC cấu hình trong UI (bảng identity_provider); bootstrap tuỳ chọn:
    bootstrap-oidc-issuer: ${OPENDBAPI_OIDC_ISSUER:}
  workspace:
    default-quotas: { endpoints: 500, datasources: 50, qps: 1000 }
spring:
  threads.virtual.enabled: true
  datasource:
    url: ${OPENDBAPI_DB_URL:jdbc:postgresql://localhost:5432/opendbapi}
server:
  port: 8520
management:
  endpoints.web.exposure.include: health,prometheus
  otlp.tracing.endpoint: ${OTEL_EXPORTER_OTLP_ENDPOINT:}
```

---

## 23. Yêu cầu phi chức năng

| Hạng mục | Yêu cầu |
|---|---|
| Hiệu năng | Overhead của pipeline (không tính thời gian DB) p99 < 5 ms; 2.000 req/s/node 4 vCPU với SELECT đơn giản đã cache pool |
| Bộ nhớ | Stream 1 triệu dòng mà heap tăng không quá 50 MB |
| Bảo mật | Không có đường nào nối input thô vào SQL; secret client được hash; secret datasource mã hoá hoặc tham chiếu ngoài; session cookie HttpOnly + SameSite + CSRF; giới hạn kích thước body (mặc định 1 MB); không log giá trị tham số |
| Khả dụng | Serving không phụ thuộc admin; mất kết nối metadata DB thì vẫn phục vụ được từ registry đã nạp |
| Tương thích | Import được dữ liệu export của DBAPI (§15.3) |

---

## 24. Kiểm thử

- **Unit:**
  - Engine template (từng thẻ, lồng nhau, `orderBy` whitelist).
  - `SqlFragment` (bind, collection, cộng với `String` phải ném lỗi, `raw` chỉ nhận hằng).
  - Kiểm tra SQL tĩnh, phân trang theo từng dialect, gộp kết quả lồng nhau, che dữ liệu, ký HMAC.
- **Integration (Testcontainers):**
  - Metadata DB luôn là PostgreSQL; datasource người dùng thử trên PostgreSQL, MySQL, SQL Server: publish → gọi API → rollback; transaction; stream 1 triệu dòng.
  - **Cách ly workspace:** mọi admin API và serving, gọi bằng user/token của workspace A với ID tài nguyên của workspace B, đều phải trả 404 (không lộ sự tồn tại). Có test riêng chạy câu SQL thiếu điều kiện `workspace_id` để xác nhận RLS vẫn chặn.
  - LDAP (container OpenLDAP) và OIDC (container Keycloak): đăng nhập, tự tạo user, đồng bộ role theo nhóm, rút khỏi nhóm thì mất quyền.
  - Luồng OAuth2 đầy đủ.
  - Try-it trả 403 thật khi chưa cấp quyền.
  - Endpoint trong thùng rác không còn được phục vụ.
- **Bảo mật:**
  - Fuzz SQLi trên tham số và tham số sort.
  - Script cố gọi `Runtime.exec`, reflection, hoặc `this.class.classLoader` phải bị chặn.
  - Cache `scope = client` không để lộ dữ liệu giữa hai service account.
- **Frontend:** Vitest + Testing Library (render component thật); Playwright cho luồng chính: tạo datasource → tạo endpoint → preview → publish → tạo service account + client → cấp quyền → Try-it → gọi thật bằng token → xem Activity. Test Playwright nằm ở `frontend/tests/e2e`, chạy bằng `npm run test:e2e` trên stack dev, mỗi lần tạo một workspace mới nên chạy lặp lại được trên cùng database.

---

## 25. Lộ trình

### 25.1 P1

1. ✅ (2026-11-16) Khung repo: Maven multi-module, Docker Compose (PostgreSQL, OpenLDAP, Keycloak cho dev), khung frontend copy từ Egret theo §17.1, Flyway schema §4 với RLS.
2. ✅ (2026-11-16) Workspace + user + đăng nhập nội bộ/LDAP/OIDC + role theo workspace + group mapping.
3. ✅ (2026-11-16) Engine template + kiểm tra SQL tĩnh + phân trang (TDD), module `opendbapi-sql`, đã chạy thử trên PostgreSQL và MySQL thật.
4. ✅ (2026-11-16) Datasource registry + CRUD + test kết nối + duyệt schema/bảng/cột, cùng màn hình Datasources.
5. ✅ (2026-11-16) Endpoint draft/validate/publish/rollback + registry + pipeline serving (§9.1, bước 1, 3, 7, 10–14, 16).
6. ✅ (2026-11-17) Service account, client, OAuth2 token, cấp quyền (§9.1 bước 4–5). Token endpoint `POST /oauth2/token`, khoá công khai tại `GET /oauth2/jwks`; khoá ký RS256 lưu trong bảng `signing_key`, private key niêm phong bằng master key. Bảng `oauth_client_directory` (không RLS, không chứa secret) cho token endpoint biết client thuộc workspace nào.
6b. ✅ (2026-11-17) Firewall IP/CIDR và rate limit (§14, §9.1 bước 2 và 6): cấu hình theo workspace (cột `workspace.serving_policy`), giới hạn riêng từng endpoint ở `limits.ratePerMinute`. Token bucket chạy trong bộ nhớ nên giới hạn được đếm riêng ở mỗi instance. Header `X-Forwarded-For` chỉ được tin khi request đến từ `opendbapi.serving.trusted-proxies`.
7. ✅ (2026-11-17) Preview + Try-it (gọi pipeline thật trong cùng tiến trình). Preview đã có từ bước 5. Lời gọi Try-it được ghi vào access log dạng dòng JSON (`actorUserId`); bảng `access_log` thuộc bước 10.
8. ✅ (2026-11-17) OpenAPI tự sinh + Swagger UI.
9. ✅ (2026-11-17) Stream NDJSON/CSV trên virtual threads.
10. ✅ (2026-11-17) Metric, tracing, log query chậm, access log ra file, trang Activity cơ bản.
11. ✅ (2026-11-17) UI theo §17: login, shell + workspace switcher, Endpoints (list/new/detail 5 tab), Datasources, Clients, Activity, Settings, Admin.
    Chỗ khác §17 trong bản P1: tạo service account và client dùng dialog trên trang Clients, không có route `/clients/new` hay `/new-client` riêng. Dialog hiện secret không đóng được bằng Escape hay click ra ngoài. Rotate secret giữ nguyên client id. Danh sách datasource chưa hiện trạng thái của lần test kết nối gần nhất, vì kết quả test chưa được lưu. Các tab Datasource dùng `?tab=`. Nhóm Output/Cache và data policy thuộc P2.

### 25.2 P2

Thứ tự chốt ngày 2026-11-17. Mỗi bước là một PR, đủ test và qua gate như P1.

12. ✅ (2026-11-17) Lộ trình P2 và test Playwright cho luồng chính (§24).
13. Che cột và lọc dòng (§12): bảng `data_policy`, áp dụng ở bước post-process và rewrite của pipeline, admin API, màn hình quản lý.
14. Rule tham số (§13.1) và kênh cảnh báo lỗi qua webhook (§13.2).
15. Bộ test case cho từng endpoint (§11.3): bảng `endpoint_test_case`, chạy bộ test trước khi publish, tab Tests.
16. Map kết quả (§9.2): `naming`, `shape`, `nest`, `types`; phân trang keyset.
17. Cache kết quả (§9.5), metric `opendbapi_cache_hits_total`.
18. Ký request HMAC (§8.2) và mã hoá response (§8.3).
19. Task Groovy `SqlFragment` (§7.4) trong sandbox.
20. Executor Elasticsearch và HTTP (§6), driver ClickHouse.
21. Thống kê đầy đủ trên trang Activity (`/activity/stats`, §21.2); lưu và hiện trạng thái lần test kết nối datasource gần nhất.
22. Export YAML (§15.1) và import từ DBAPI (§15.3).
23. Giao diện tiếng Việt (`react-i18next`, §17).

---

## 26. Quyết định đã chốt và câu hỏi còn mở

### Đã chốt (2026-11-16)

| # | Câu hỏi | Quyết định |
|---|---|---|
| 1 | Metadata DB | Chỉ PostgreSQL |
| 2 | Đăng nhập người viết | Nội bộ + LDAP + SSO (OIDC), cả ba ở P1 |
| 3 | Độc lập hay tích hợp Egret | Độc lập |
| 4 | Ngôn ngữ UI | Tiếng Anh, i18n tiếng Việt ở P2 |
| 5 | Đa tenant | Có, theo workspace, từ P1 |
| 6 | Mức dùng lại Egret | Chỉ lấy khung (§17.1); UI thiết kế lại gọn cho tính năng DBAPI |
| 7 | Plugin SPI (2026-11-17) | Bỏ hẳn. Cần gì thì viết thẳng thành tính năng (§13) |

### Còn mở

1. Có cần SAML 2.0 không, hay OIDC là đủ? (Hiện để P3.)
2. Slug workspace nằm trên path (`/api/{ws}/…`, đã chọn) hay cần thêm tuỳ chọn subdomain (`{ws}.api.corp.local`)?
3. Access log lưu cùng PostgreSQL metadata (partition theo tháng) có đủ không, hay cần ClickHouse từ đầu?

---

## Phụ lục: Tham chiếu Egret

| Chủ đề | File |
|---|---|
| Quy tắc frontend | `docs/en/frontend-rules.md` |
| Thiết kế Data API (mô hình, vòng đời, OAuth2, cache theo client) | `docs/en/specs/2026-08-14-data-api-design.md` |
| Tham số & render | `docs/en/specs/2026-08-15-data-api-parameters-design.md` |
| Điều hướng, Try-it qua proxy | `docs/en/specs/2026-08-16-data-api-p5-ui-decisions.md` |
| Preview, các tab Query/Config, ba nút Save/Preview/Publish | `docs/en/specs/2026-08-17-data-api-authoring-loop-design.md` |
| Trang danh sách, trang tạo, bảng setting, thùng rác | `docs/en/specs/2026-08-18-data-api-frontend-rework-design.md` |
| Component tham khảo | `frontend/src/components/{layout,ui}`, `frontend/src/routes/explore/dataapi/` |
