import type { DatasourceView, SaveDatasource, SaveDatasourceDialect } from "@/api/generated/model";

export type Dialect = SaveDatasourceDialect;

export const DIALECT_LABEL: Record<Dialect, string> = {
  POSTGRESQL: "PostgreSQL",
  MYSQL: "MySQL",
  MARIADB: "MariaDB",
  SQLSERVER: "SQL Server",
  ORACLE: "Oracle",
};

/** What the database name field means for each dialect. */
export const DATABASE_LABEL: Record<Dialect, string> = {
  POSTGRESQL: "Database",
  MYSQL: "Database",
  MARIADB: "Database",
  SQLSERVER: "Database",
  ORACLE: "Service name",
};

export type PropertyRow = { key: string; value: string };

/** Form state: strings for inputs, converted once on submit. */
export type DatasourceForm = {
  name: string;
  description: string;
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
};

export function emptyForm(): DatasourceForm {
  return {
    name: "",
    description: "",
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
  };
}

export function formFrom(d: DatasourceView): DatasourceForm {
  return {
    name: d.name,
    description: d.description ?? "",
    dialect: d.dialect,
    host: d.host,
    port: String(d.port),
    database: d.database,
    username: d.username,
    password: "",
    properties: Object.entries(d.properties).map(([key, value]) => ({ key, value })),
    maximumPoolSize: String(d.pool.maximumPoolSize ?? 10),
    minimumIdle: String(d.pool.minimumIdle ?? 0),
    connectionTimeoutMs: String(d.pool.connectionTimeoutMs ?? 10000),
    readOnly: d.readOnly,
  };
}

const intOrNull = (s: string): number | null => (s.trim() === "" ? null : Number.parseInt(s, 10));

export function toRequest(f: DatasourceForm): SaveDatasource {
  const properties: Record<string, string> = {};
  for (const row of f.properties) {
    if (row.key.trim()) properties[row.key.trim()] = row.value.trim();
  }
  return {
    name: f.name.trim(),
    description: f.description.trim() || null,
    dialect: f.dialect,
    host: f.host.trim(),
    port: intOrNull(f.port),
    database: f.database.trim(),
    username: f.username.trim(),
    password: f.password === "" ? null : f.password,
    properties,
    pool: {
      maximumPoolSize: intOrNull(f.maximumPoolSize),
      minimumIdle: intOrNull(f.minimumIdle),
      connectionTimeoutMs: intOrNull(f.connectionTimeoutMs),
    },
    readOnly: f.readOnly,
  };
}
