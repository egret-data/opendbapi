import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

import type { DataPolicy, DatasourceView, ServiceAccountDetail } from "@/api/generated/model";
import { fromRows, toRows } from "@/routes/workspace/clients/AttributesSection";
import { ME_ALICE, json, mockServer, renderApp } from "./testServer";

afterEach(() => vi.unstubAllGlobals());

const asAdmin = { ...ME_ALICE, workspaces: [{ id: "w1", slug: "sales", name: "Sales", role: "admin" as const, configLocked: false }] };

const DS: DatasourceView = {
  id: "ds1", name: "crm", description: null, dialect: "POSTGRESQL", host: "db", port: 5432, database: "crm", username: "r",
  properties: {}, pool: { maximumPoolSize: 10, minimumIdle: 0, connectionTimeoutMs: 10000 }, readOnly: true, hasPassword: true,
  passwordEnv: null, createdAt: "2026-11-17T00:00:00Z", updatedAt: "2026-11-17T00:00:00Z", lastTest: null,
  type: "jdbc", http: null,
};

const EMAIL_MASK: DataPolicy = {
  id: "p1", kind: "mask", datasourceId: "ds1", endpointId: null, column: "customers.email", mask: { type: "email" },
  attribute: null, exemptServiceAccounts: ["sa1"], createdAt: "2026-11-17T00:00:00Z", updatedAt: "2026-11-17T00:00:00Z",
};

const SA: ServiceAccountDetail = {
  summary: { id: "sa1", name: "support", description: null, disabled: false, createdAt: "2026-11-17T00:00:00Z",
    clientCount: 0, grantCount: 0, lastUsedAt: null, attributes: { tenant: "acme", regions: [1, 2] } },
  clients: [],
  grants: [],
};

describe("Data policies", () => {
  it("lists a datasource's masks and adds a partial mask", async () => {
    // Arrange
    const server = mockServer({
      "GET /admin/api/me": () => json(asAdmin),
      "GET /admin/api/ws/sales/datasources/ds1": () => json(DS),
      "GET /admin/api/ws/sales/datasources/dialects": () => json([]),
      "GET /admin/api/ws/sales/data-policies": () => json([EMAIL_MASK]),
      "GET /admin/api/ws/sales/service-accounts": () => json([SA.summary]),
      "POST /admin/api/ws/sales/data-policies": () => json({ ...EMAIL_MASK, id: "p2" }, 201),
    });
    const user = userEvent.setup();
    renderApp("/w/sales/datasources/ds1?tab=policies");

    // Act
    const row = (await screen.findByText("customers.email")).closest("tr")!;
    await user.click(screen.getByRole("button", { name: "Add mask" }));
    const dialog = screen.getByRole("dialog", { name: "New mask" });
    await user.type(within(dialog).getByLabelText("Table column"), "customers.phone");
    await user.clear(within(dialog).getByLabelText("Keep at end"));
    await user.type(within(dialog).getByLabelText("Keep at end"), "3");
    await user.click(within(dialog).getByRole("button", { name: "Save" }));

    // Assert
    expect(within(row).getByText(/n\*\*\*@example.com/)).toBeInTheDocument();
    expect(within(row).getByText("support")).toBeInTheDocument();
    await waitFor(() => expect(server.calls.some((c) => c.key === "POST /admin/api/ws/sales/data-policies")).toBe(true));
    const post = server.calls.find((c) => c.key === "POST /admin/api/ws/sales/data-policies")!;
    expect(JSON.parse(String(post.init?.body))).toEqual({
      kind: "mask", endpointId: null, datasourceId: "ds1", column: "customers.phone",
      mask: { type: "partial", keepStart: 2, keepEnd: 3 }, attribute: null, exemptServiceAccounts: [],
    });
  });

  it("edits a service account's typed attributes", async () => {
    // Arrange
    const server = mockServer({
      "GET /admin/api/me": () => json(asAdmin),
      "GET /admin/api/system/info": () => json({ version: "dev", mode: "standalone", servingBaseUrl: "http://h/api", tokenUrl: "http://h/t" }),
      "GET /admin/api/ws/sales/endpoints": () => json([]),
      "GET /admin/api/ws/sales/groups": () => json([]),
      "GET /admin/api/ws/sales/service-accounts/sa1": () => json(SA),
      "PATCH /admin/api/ws/sales/service-accounts/sa1": () => json(SA),
    });
    const user = userEvent.setup();
    renderApp("/w/sales/clients/sa1");

    // Act
    await user.click(await screen.findByRole("button", { name: "Edit attributes" }));
    const dialog = screen.getByRole("dialog", { name: "Edit attributes" });
    await user.clear(within(dialog).getByLabelText("Attribute 1 value"));
    await user.type(within(dialog).getByLabelText("Attribute 1 value"), "globex");
    await user.click(within(dialog).getByRole("button", { name: "Save" }));

    // Assert
    expect(screen.getByText("1, 2")).toBeInTheDocument();
    await waitFor(() => expect(server.calls.some((c) => c.key === "PATCH /admin/api/ws/sales/service-accounts/sa1")).toBe(true));
    const patch = server.calls.find((c) => c.key === "PATCH /admin/api/ws/sales/service-accounts/sa1")!;
    expect(JSON.parse(String(patch.init?.body)).attributes).toEqual({ tenant: "globex", regions: [1, 2] });
  });

  it("converts attribute rows both ways and rejects bad numbers", () => {
    // Act
    const rows = toRows({ tenant: "acme", level: 3, regions: ["n", "s"], ids: [1, 2] });

    // Assert
    expect(rows.map((r) => r.kind)).toEqual(["text", "number", "texts", "numbers"]);
    expect(fromRows(rows)).toEqual({ tenant: "acme", level: 3, regions: ["n", "s"], ids: [1, 2] });
    expect(fromRows([{ name: "level", kind: "number", value: "three" }])).toMatch(/must be a number/);
  });
});
