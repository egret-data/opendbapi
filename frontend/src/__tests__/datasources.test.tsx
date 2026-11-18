import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

import type { DatasourceView } from "@/api/generated/model";
import { formFrom, toRequest } from "@/routes/workspace/datasources/datasourceForm";
import { defaultSchema } from "@/routes/workspace/datasources/DatasourceDetailPage";
import { ME_ALICE, json, mockServer, problem, renderApp } from "./testServer";

afterEach(() => vi.unstubAllGlobals());

const DS: DatasourceView = {
  id: "ds1",
  name: "shop",
  description: null,
  dialect: "POSTGRESQL",
  host: "db.corp.local",
  port: 5432,
  database: "shop",
  username: "reader",
  properties: { sslmode: "require" },
  pool: { maximumPoolSize: 10, minimumIdle: 0, connectionTimeoutMs: 10000 },
  readOnly: true,
  hasPassword: true,
  passwordEnv: null,
  createdAt: "2026-11-16T00:00:00Z",
  updatedAt: "2026-11-16T00:00:00Z",
  lastTest: null,
  type: "jdbc",
  http: null,
};

const DIALECTS = [
  { dialect: "POSTGRESQL", defaultPort: 5432, allowedProperties: ["sslmode", "currentSchema"], transactional: true, passwordOptional: false },
  { dialect: "MYSQL", defaultPort: 3306, allowedProperties: ["sslMode"], transactional: true, passwordOptional: false },
  { dialect: "TRINO", defaultPort: 8080, allowedProperties: ["SSL"], transactional: true, passwordOptional: true },
  { dialect: "CLICKHOUSE", defaultPort: 8123, allowedProperties: ["ssl"], transactional: false, passwordOptional: false },
];

const meAs = (role: "admin" | "author" | "viewer") => ({
  ...ME_ALICE,
  workspaces: [{ id: "w1", slug: "sales", name: "Sales", role }],
});

describe("datasource form", () => {
  it("round-trips a datasource and keeps the stored password when the field is empty", () => {
    // Act
    const request = toRequest(formFrom(DS));

    // Assert
    expect(request).toEqual({
      name: "shop",
      description: null,
      dialect: "POSTGRESQL",
      host: "db.corp.local",
      port: 5432,
      database: "shop",
      username: "reader",
      password: null,
      properties: { sslmode: "require" },
      pool: { maximumPoolSize: 10, minimumIdle: 0, connectionTimeoutMs: 10000 },
      readOnly: true,
    });
  });
});

describe("default schema", () => {
  it("prefers the configured schema, then the database's usual default", () => {
    // Act + Assert
    expect(defaultSchema(["opendbapi", "public", "shop"], { ...DS, properties: { currentSchema: "shop" } })).toBe("shop");
    expect(defaultSchema(["opendbapi", "public", "shop"], { ...DS, properties: {} })).toBe("public");
    expect(defaultSchema(["dbo", "sales"], { ...DS, dialect: "SQLSERVER", properties: {} })).toBe("dbo");
    expect(defaultSchema(["READER", "HR"], { ...DS, dialect: "ORACLE", properties: {}, username: "reader" })).toBe("READER");
    expect(defaultSchema(["default", "sales"], { ...DS, dialect: "TRINO", database: "hive.sales", properties: {} })).toBe("sales");
    expect(defaultSchema(["shop"], { ...DS, dialect: "STARROCKS", database: "hive_catalog.shop", properties: {} })).toBe("shop");
    expect(defaultSchema(["metrics", "default"], { ...DS, dialect: "CLICKHOUSE", database: "x", properties: {} })).toBe("default");
  });
});

describe("Datasources pages", () => {
  it("creates a datasource and shows the server's refusal verbatim", async () => {
    // Arrange
    const server = mockServer({
      "GET /admin/api/me": () => json(meAs("admin")),
      "GET /admin/api/ws/sales/datasources/dialects": () => json(DIALECTS),
      "POST /admin/api/ws/sales/datasources": () =>
        problem(400, "Driver property 'socketFactory' is not allowed for POSTGRESQL.", "invalid_datasource"),
    });
    const user = userEvent.setup();
    renderApp("/w/sales/datasources/new");

    // Act
    await user.type(await screen.findByLabelText("Name"), "warehouse");
    await user.type(screen.getByLabelText("Host"), "pg.corp.local");
    await user.type(screen.getByLabelText("Database"), "dw");
    await user.type(screen.getByLabelText("Username"), "reader");
    await user.type(screen.getByLabelText("Password"), "env:DW_PASSWORD");
    await user.click(screen.getByRole("button", { name: "Create datasource" }));

    // Assert
    expect(await screen.findByRole("alert")).toHaveTextContent("Driver property 'socketFactory' is not allowed");
    const post = server.calls.find((c) => c.key === "POST /admin/api/ws/sales/datasources");
    expect(JSON.parse(String(post?.init?.body))).toMatchObject({
      name: "warehouse",
      dialect: "POSTGRESQL",
      host: "pg.corp.local",
      port: null,
      database: "dw",
      password: "env:DW_PASSWORD",
      readOnly: true,
    });
  });

  it("creates a Trino datasource on a catalog without a password", async () => {
    // Arrange
    const server = mockServer({
      "GET /admin/api/me": () => json(meAs("admin")),
      "GET /admin/api/ws/sales/datasources/dialects": () => json(DIALECTS),
      "POST /admin/api/ws/sales/datasources": () => json({ ...DS, id: "ds9", dialect: "TRINO", port: 8080, database: "hive.sales" }, 201),
    });
    const user = userEvent.setup();
    renderApp("/w/sales/datasources/new");

    // Act
    await user.type(await screen.findByLabelText("Name"), "lake");
    await user.click(screen.getByRole("combobox", { name: "Database type" }));
    await user.click(screen.getByRole("option", { name: "Trino" }));
    await user.type(screen.getByLabelText("Host"), "trino.corp.local");
    await user.type(screen.getByLabelText("Catalog"), "hive.sales");
    await user.type(screen.getByLabelText("Username"), "etl");
    await user.click(screen.getByRole("button", { name: "Create datasource" }));

    // Assert
    await waitFor(() => expect(server.calls.some((c) => c.key === "POST /admin/api/ws/sales/datasources")).toBe(true));
    const post = server.calls.find((c) => c.key === "POST /admin/api/ws/sales/datasources");
    expect(JSON.parse(String(post?.init?.body))).toMatchObject({ dialect: "TRINO", database: "hive.sales", password: null });
  });

  it("creates an HTTP API datasource with an API key", async () => {
    // Arrange
    const server = mockServer({
      "GET /admin/api/me": () => json(meAs("admin")),
      "GET /admin/api/ws/sales/datasources/dialects": () => json(DIALECTS),
      "POST /admin/api/ws/sales/datasources": () => json({ ...DS, id: "ds9", type: "http", dialect: null,
        http: { baseUrl: "https://api.example.com/v1", auth: "header", headerName: "X-API-Key", headers: {} } }, 201),
    });
    const user = userEvent.setup();
    renderApp("/w/sales/datasources/new");

    // Act
    await user.type(await screen.findByLabelText("Name"), "orders-api");
    await user.click(screen.getByRole("combobox", { name: "Type" }));
    await user.click(screen.getByRole("option", { name: "HTTP API" }));
    await user.type(screen.getByLabelText("Base URL"), "https://api.example.com/v1");
    await user.click(screen.getByRole("combobox", { name: "Authentication" }));
    await user.click(screen.getByRole("option", { name: "API key in a header" }));
    await user.type(screen.getByLabelText("API key"), "secret:ORDERS_KEY");
    await user.click(screen.getByRole("button", { name: "Add header" }));
    await user.type(screen.getByLabelText("Header 1 name"), "X-Tenant");
    await user.type(screen.getByLabelText("Header 1 value"), "acme");
    await user.click(screen.getByRole("button", { name: "Create datasource" }));

    // Assert
    await waitFor(() => expect(server.calls.some((c) => c.key === "POST /admin/api/ws/sales/datasources")).toBe(true));
    const post = server.calls.find((c) => c.key === "POST /admin/api/ws/sales/datasources");
    expect(JSON.parse(String(post?.init?.body))).toEqual({
      name: "orders-api", description: null, type: "http", password: "secret:ORDERS_KEY", readOnly: true,
      http: { baseUrl: "https://api.example.com/v1", auth: "header", username: null, headerName: "X-API-Key",
        headers: { "X-Tenant": "acme" }, connectTimeoutMs: 10000 },
    });
  });

  it("says when the database cannot roll back", async () => {
    // Arrange
    mockServer({
      "GET /admin/api/me": () => json(meAs("admin")),
      "GET /admin/api/ws/sales/datasources/dialects": () => json(DIALECTS),
    });
    const user = userEvent.setup();
    renderApp("/w/sales/datasources/new");

    // Act
    await user.click(await screen.findByRole("combobox", { name: "Database type" }));
    await user.click(screen.getByRole("option", { name: "ClickHouse" }));

    // Assert
    expect((await screen.findAllByText(/ClickHouse cannot roll back/)).length).toBeGreaterThan(0);
    expect(screen.getByLabelText("Password")).toBeRequired();
  });

  it("lists each datasource with its last connection test", async () => {
    // Arrange
    mockServer({
      "GET /admin/api/me": () => json({ ...ME_ALICE, workspaces: [{ id: "w1", slug: "sales", name: "Sales", role: "admin", configLocked: false }] }),
      "GET /admin/api/ws/sales/datasources": () => json([
        { ...DS, lastTest: { at: "2026-11-17T00:00:00Z", ok: true, message: "Connected to PostgreSQL 16 in 4 ms." } },
        { ...DS, id: "ds2", name: "legacy", lastTest: { at: "2026-11-17T00:00:00Z", ok: false, message: "Connection refused" } },
        { ...DS, id: "ds3", name: "fresh" },
      ]),
      "GET /admin/api/ws/sales/endpoints": () => json([]),
    });

    // Act
    renderApp("/w/sales/datasources");

    // Assert
    const legacy = (await screen.findByRole("link", { name: "legacy" })).closest("tr")!;
    expect(within(legacy).getByText("Failed").closest("[title]")).toHaveAttribute("title", expect.stringContaining("Connection refused"));
    expect(within(screen.getByRole("link", { name: "shop" }).closest("tr")!).getByText("Connected")).toBeInTheDocument();
    expect(within(screen.getByRole("link", { name: "fresh" }).closest("tr")!).getByText("Not tested")).toBeInTheDocument();
  });

  it("authors cannot open the create page", async () => {
    // Arrange
    mockServer({ "GET /admin/api/me": () => json(meAs("author")) });

    // Act
    renderApp("/w/sales/datasources/new");

    // Assert
    expect(await screen.findByRole("heading", { name: "You don't have access" })).toBeInTheDocument();
  });

  it("viewers see settings read-only and no table browser", async () => {
    // Arrange
    const user = userEvent.setup();
    mockServer({
      "GET /admin/api/me": () => json(meAs("viewer")),
      "GET /admin/api/ws/sales/datasources/ds1": () => json(DS),
      "GET /admin/api/ws/sales/datasources/dialects": () => json(DIALECTS),
    });
    renderApp("/w/sales/datasources/ds1");

    // Assert: settings
    expect(await screen.findByText("Only workspace admins can change datasources.")).toBeInTheDocument();
    expect(screen.getByLabelText("Host")).toBeDisabled();
    expect(screen.queryByRole("button", { name: "Save changes" })).not.toBeInTheDocument();

    // Act
    await user.click(screen.getByRole("tab", { name: "Tables" }));

    // Assert: tables
    expect(await screen.findByText("Tables are visible to authors")).toBeInTheDocument();
  });

  it("authors browse tables and their columns", async () => {
    // Arrange
    const user = userEvent.setup();
    mockServer({
      "GET /admin/api/me": () => json(meAs("author")),
      "GET /admin/api/ws/sales/datasources/ds1": () => json(DS),
      "GET /admin/api/ws/sales/datasources/dialects": () => json(DIALECTS),
      "GET /admin/api/ws/sales/datasources/ds1/schemas": () => json(["public", "shop"]),
      "GET /admin/api/ws/sales/datasources/ds1/tables": (_init, url) =>
        json({
          items: url.includes("schema=public")
            ? [{ schema: "public", name: "orders", type: "TABLE", remarks: null }]
            : [],
          truncated: false,
        }),
      "GET /admin/api/ws/sales/datasources/ds1/columns": () =>
        json([
          { name: "id", type: "int4", size: 10, nullable: false, position: 1 },
          { name: "customer", type: "text", size: 0, nullable: true, position: 2 },
        ]),
    });
    renderApp("/w/sales/datasources/ds1?tab=tables");

    // Act
    await user.click(await screen.findByRole("button", { name: "orders" }));

    // Assert
    expect(await screen.findByText("customer")).toBeInTheDocument();
    await waitFor(() => expect(screen.getByTestId("location")).toHaveTextContent("table=orders"));
  });
});
