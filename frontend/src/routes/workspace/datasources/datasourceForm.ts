import type { DatasourceView, SaveDatasource, SaveDatasourceDialect } from "@/api/generated/model";

export type Dialect = NonNullable<SaveDatasourceDialect>;
export type SourceType = "jdbc" | "http";
export type HttpAuth = "none" | "bearer" | "basic" | "header";

export const DIALECT_LABEL: Record<Dialect, string> = {
  POSTGRESQL: "PostgreSQL",
  MYSQL: "MySQL",
  MARIADB: "MariaDB",
  SQLSERVER: "SQL Server",
  ORACLE: "Oracle",
  TRINO: "Trino",
  STARROCKS: "StarRocks",
  DORIS: "Apache Doris",
  CLICKHOUSE: "ClickHouse",
};

/** "HTTP API", or the database type. */
export function typeLabel(d: Pick<DatasourceView, "type" | "dialect">): string {
  return d.type === "http" || !d.dialect ? "HTTP API" : DIALECT_LABEL[d.dialect];
}

/** What the database name field means for each dialect. */
export const DATABASE_LABEL: Record<Dialect, string> = {
  POSTGRESQL: "Database",
  MYSQL: "Database",
  MARIADB: "Database",
  SQLSERVER: "Database",
  ORACLE: "Service name",
  TRINO: "Catalog",
  STARROCKS: "Database",
  DORIS: "Database",
  CLICKHOUSE: "Database",
};

/** What to type in the database field, for each dialect. */
export const DATABASE_HELP: Record<Dialect, string> = {
  POSTGRESQL: "The database to connect to",
  MYSQL: "The database to connect to",
  MARIADB: "The database to connect to",
  SQLSERVER: "The database to connect to",
  ORACLE: "The service name, e.g. ORCLPDB1",
  TRINO: "A catalog, e.g. hive, or catalog.schema to make that schema the default",
  STARROCKS: "A database, or catalog.database for an external catalog",
  DORIS: "A database, or catalog.database for an external catalog",
  CLICKHOUSE: "The database to connect to",
};

/** The label of the secret field for each kind of HTTP authentication. */
export const SECRET_LABEL: Record<HttpAuth, string> = {
  none: "Secret",
  bearer: "Token",
  basic: "Password",
  header: "API key",
};

export type PropertyRow = { key: string; value: string };

/** Form state: strings for inputs, converted once on submit. */
export type DatasourceForm = {
  name: string;
  description: string;
  type: SourceType;
  dialect: Dialect;
  host: string;
  port: string;
  database: string;
  username: string;
  /** Empty: keep the stored password (edit) — required when creating. */
  password: string;
  properties: PropertyRow[];
  maximumPoolSize: string;
  minimumIdle: string;
  connectionTimeoutMs: string;
  readOnly: boolean;
  baseUrl: string;
  auth: HttpAuth;
  httpUsername: string;
  headerName: string;
  headers: PropertyRow[];
};

export function emptyForm(): DatasourceForm {
  return {
    name: "",
    description: "",
    type: "jdbc",
    dialect: "POSTGRESQL",
    host: "",
    port: "",
    database: "",
    username: "",
    password: "",
    properties: [],
    maximumPoolSize: "10",
    minimumIdle: "0",
    connectionTimeoutMs: "10000",
    // Safer default: endpoints that only read cannot write by accident.
    readOnly: true,
    baseUrl: "",
    auth: "bearer",
    httpUsername: "",
    headerName: "X-API-Key",
    headers: [],
  };
}

export function formFrom(d: DatasourceView): DatasourceForm {
  const empty = emptyForm();
  if (d.type === "http" && d.http) {
    return {
      ...empty,
      name: d.name,
      description: d.description ?? "",
      type: "http",
      readOnly: d.readOnly,
      baseUrl: d.http.baseUrl,
      auth: d.http.auth as HttpAuth,
      httpUsername: d.http.username ?? "",
      headerName: d.http.headerName ?? empty.headerName,
      headers: Object.entries(d.http.headers ?? {}).map(([key, value]) => ({ key, value: value ?? "" })),
      connectionTimeoutMs: String(d.http.connectTimeoutMs ?? 10000),
    };
  }
  return {
    ...empty,
    name: d.name,
    description: d.description ?? "",
    dialect: d.dialect ?? "POSTGRESQL",
    host: d.host,
    port: String(d.port),
    database: d.database ?? "",
    username: d.username ?? "",
    properties: Object.entries(d.properties).map(([key, value]) => ({ key, value })),
    maximumPoolSize: String(d.pool?.maximumPoolSize ?? 10),
    minimumIdle: String(d.pool?.minimumIdle ?? 0),
    connectionTimeoutMs: String(d.pool?.connectionTimeoutMs ?? 10000),
    readOnly: d.readOnly,
  };
}

const intOrNull = (s: string): number | null => (s.trim() === "" ? null : Number.parseInt(s, 10));

function rows(list: PropertyRow[]): Record<string, string> {
  const out: Record<string, string> = {};
  for (const row of list) {
    if (row.key.trim()) out[row.key.trim()] = row.value.trim();
  }
  return out;
}

export function toRequest(f: DatasourceForm): SaveDatasource {
  const password = f.password === "" ? null : f.password;
  if (f.type === "http") {
    return {
      name: f.name.trim(),
      description: f.description.trim() || null,
      type: "http",
      password,
      readOnly: f.readOnly,
      http: {
        baseUrl: f.baseUrl.trim(),
        auth: f.auth,
        username: f.auth === "basic" ? f.httpUsername.trim() : null,
        headerName: f.auth === "header" ? f.headerName.trim() : null,
        headers: rows(f.headers),
        connectTimeoutMs: intOrNull(f.connectionTimeoutMs),
      },
    };
  }
  return {
    name: f.name.trim(),
    description: f.description.trim() || null,
    dialect: f.dialect,
    host: f.host.trim(),
    port: intOrNull(f.port),
    database: f.database.trim(),
    username: f.username.trim(),
    password,
    properties: rows(f.properties),
    pool: {
      maximumPoolSize: intOrNull(f.maximumPoolSize),
      minimumIdle: intOrNull(f.minimumIdle),
      connectionTimeoutMs: intOrNull(f.connectionTimeoutMs),
    },
    readOnly: f.readOnly,
  };
}
