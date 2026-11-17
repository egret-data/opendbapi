import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

import type { EndpointDetail, EndpointSummary } from "@/api/generated/model";
import { ME_ALICE, json, mockServer, renderApp } from "./testServer";

vi.mock("@/components/monaco/MonacoEditor", () => ({
  default: ({ value }: { value: string }) => <textarea aria-label="SQL" readOnly value={value} />,
  MonacoDiffEditor: () => null,
}));

afterEach(() => vi.unstubAllGlobals());

const asAuthor = { ...ME_ALICE, workspaces: [{ id: "w1", slug: "sales", name: "Sales", role: "author" as const }] };
const V1: EndpointSummary = {
  id: "e1", name: "Orders", description: null, method: "GET", path: "orders", state: "live", liveVersion: 3, dirty: false,
  access: "public", groupId: null, datasourceId: "ds1", trashed: false, updatedAt: "2026-11-17T00:00:00Z",
  major: 1, deprecatedAt: "2026-11-17T00:00:00Z", sunsetAt: "2027-06-30T00:00:00Z",
};
const DETAIL: EndpointDetail = {
  summary: V1, createdAt: "2026-11-01T00:00:00Z",
  definition: {
    params: [],
    task: { type: "sql", datasourceId: "ds1", transaction: false, statements: [{ id: "main", template: "select 1" }] },
    pagination: { enabled: false, defaultSize: 20, maxSize: 500, count: true },
    limits: { timeoutSeconds: 30, maxRows: 10000 },
    streaming: { allowed: false, formats: ["ndjson", "csv"] },
    rules: [],
    alerts: { enabled: false },
    output: { naming: "as_is", shape: "list", types: {}, nest: [] },
    cache: { enabled: false, ttlSeconds: 60, scope: "client" },
  },
};

describe("Major versions", () => {
  it("shows the deprecation and the lineage, and creates the next major at the chosen path", async () => {
    // Arrange
    const server = mockServer({
      "GET /admin/api/me": () => json(asAuthor),
      "GET /admin/api/system/info": () => json({ version: "dev", mode: "standalone", servingBaseUrl: "http://h/api" }),
      "GET /admin/api/ws/sales/datasources": () => json([{ id: "ds1", name: "crm" }]),
      "GET /admin/api/ws/sales/groups": () => json([]),
      "GET /admin/api/ws/sales/endpoints/e1": () => json(DETAIL),
      "GET /admin/api/ws/sales/endpoints/e1/versions": () => json([]),
      "GET /admin/api/ws/sales/endpoints/e1/lineage": () => json([
        { id: "e1", name: "Orders", method: "GET", path: "orders", major: 1, state: "live", liveVersion: 3,
          deprecatedAt: "2026-11-17T00:00:00Z", sunsetAt: "2027-06-30T00:00:00Z" },
        { id: "e2", name: "Orders v2", method: "GET", path: "v2/orders", major: 2, state: "draft", liveVersion: null, deprecatedAt: null, sunsetAt: null },
      ]),
      "POST /admin/api/ws/sales/endpoints/e1:new-major": () => json({ ...DETAIL, summary: { ...V1, id: "e3", major: 3 } }, 201),
    });
    const user = userEvent.setup();
    renderApp("/w/sales/endpoints/e1/versions");

    // Act
    const lineage = await screen.findByRole("region", { name: "Major versions" });
    await user.click(screen.getByRole("button", { name: "More actions" }));
    await user.click(await screen.findByRole("menuitem", { name: "New major version…" }));
    const dialog = screen.getByRole("dialog", { name: "New major version" });
    await user.type(within(dialog).getByLabelText("Path of the new version"), "v3/orders");
    await user.click(within(dialog).getByRole("button", { name: "Create draft" }));

    // Assert
    expect(screen.getAllByText(/Deprecated · sunset/).length).toBeGreaterThan(0);
    expect(within(lineage).getByRole("link", { name: "GET /v2/orders" })).toHaveAttribute("href", "/w/sales/endpoints/e2/versions");
    await waitFor(() => expect(server.calls.some((c) => c.key === "POST /admin/api/ws/sales/endpoints/e1:new-major")).toBe(true));
    const call = server.calls.find((c) => c.key === "POST /admin/api/ws/sales/endpoints/e1:new-major")!;
    expect(JSON.parse(String(call.init?.body))).toEqual({ path: "v3/orders" });
  });
});
