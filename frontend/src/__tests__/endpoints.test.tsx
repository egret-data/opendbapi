import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

import type { EndpointDetail, EndpointSummary } from "@/api/generated/model";
import { detectParams } from "@/routes/workspace/endpoints/endpointForm";
import { ME_ALICE, json, mockServer, renderApp } from "./testServer";

// Monaco does not run in jsdom; a textarea stands in for it.
vi.mock("@/components/monaco/MonacoEditor", () => ({
  default: ({ value, onChange }: { value: string; onChange?: (v: string) => void }) => (
    <textarea aria-label="SQL" value={value} onChange={(e) => onChange?.(e.target.value)} />
  ),
  MonacoDiffEditor: () => <div>diff</div>,
}));

afterEach(() => vi.unstubAllGlobals());

const meAs = (role: "author" | "viewer") => ({ ...ME_ALICE, workspaces: [{ id: "w1", slug: "sales", name: "Sales", role }] });

const SUMMARY: EndpointSummary = {
  id: "e1", name: "Customers", description: null, method: "GET", path: "customers", state: "live", liveVersion: 2,
  dirty: true, access: "private", groupId: null, datasourceId: "ds1", trashed: false, updatedAt: "2026-11-16T00:00:00Z",
};

const DETAIL: EndpointDetail = {
  summary: SUMMARY,
  createdAt: "2026-11-16T00:00:00Z",
  definition: {
    params: [{ name: "tier", in: "query", type: "string", required: false }],
    task: { type: "sql", datasourceId: "ds1", transaction: false, statements: [{ id: "main", template: "select id from customers where tier = #{tier}" }] },
    pagination: { enabled: false, defaultSize: 20, maxSize: 500, count: true },
    limits: { timeoutSeconds: 30, maxRows: 10000 },
    streaming: { allowed: false, formats: ["ndjson", "csv"] },
    rules: [],
    alerts: { enabled: false },
  },
};

const common = {
  "GET /admin/api/system/info": () => json({ version: "dev", mode: "standalone", servingBaseUrl: "http://h/api" }),
  "GET /admin/api/ws/sales/datasources": () => json([{ id: "ds1", name: "crm" }]),
  "GET /admin/api/ws/sales/groups": () => json([]),
};

describe("detectParams", () => {
  it("finds placeholders, test variables and orderBy params but not bind or loop names", () => {
    // Arrange
    const sql = `<bind name="kw" value="'%' + q + '%'"/>
      select * from t <where><if test="status != null and ids.size() > 0">a = #{status}</if>
      <foreach collection="ids" item="id">#{id}</foreach> and name like #{kw}</where>
      <orderBy param="sort" allow="a"/> limit #{page}`;

    // Act + Assert
    expect(detectParams(sql).sort()).toEqual(["ids", "q", "sort", "status"]);
  });
});

describe("Endpoints", () => {
  it("lists endpoints with state, version and unpublished changes", async () => {
    // Arrange
    mockServer({
      ...common,
      "GET /admin/api/me": () => json(meAs("author")),
      "GET /admin/api/ws/sales/endpoints": () => json([SUMMARY, { ...SUMMARY, id: "e2", name: "Draft one", state: "draft", liveVersion: null, path: "d" }]),
    });

    // Act
    renderApp("/w/sales/endpoints");

    // Assert
    const row = (await screen.findByRole("link", { name: "Customers" })).closest("tr")!;
    expect(within(row).getByText("Live")).toBeInTheDocument();
    expect(within(row).getByText("v2")).toBeInTheDocument();
    expect(within(row).getByText(/unpublished changes/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /1\s*drafts?/ })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "API docs" })).toHaveAttribute("href", "http://h/api/sales/docs.html");
    expect(screen.getByRole("link", { name: "Download OpenAPI" })).toHaveAttribute("href", "/admin/api/ws/sales/openapi.json");
  });

  it("creates an endpoint and opens its editor", async () => {
    // Arrange
    const server = mockServer({
      ...common,
      "GET /admin/api/me": () => json(meAs("author")),
      "POST /admin/api/ws/sales/endpoints": () => json(DETAIL, 201),
      "GET /admin/api/ws/sales/endpoints/e1": () => json(DETAIL),
    });
    const user = userEvent.setup();
    renderApp("/w/sales/endpoints/new");

    // Act
    await user.type(await screen.findByLabelText("Name"), "Customers");
    await user.type(screen.getByLabelText("Path"), "/customers");
    await user.click(screen.getByRole("combobox", { name: "Datasource" }));
    await user.click(screen.getByRole("option", { name: "crm" }));
    await user.click(screen.getByRole("button", { name: "Create and edit query" }));

    // Assert
    await waitFor(() => expect(screen.getByTestId("location")).toHaveTextContent("/w/sales/endpoints/e1"));
    const post = server.calls.find((c) => c.key === "POST /admin/api/ws/sales/endpoints");
    expect(JSON.parse(String(post?.init?.body))).toEqual({
      name: "Customers", method: "GET", path: "customers", access: "private", datasourceId: "ds1", groupId: null,
    });
  });

  it("viewers read the query but cannot save, publish or preview", async () => {
    // Arrange
    mockServer({ ...common, "GET /admin/api/me": () => json(meAs("viewer")), "GET /admin/api/ws/sales/endpoints/e1": () => json(DETAIL) });

    // Act
    renderApp("/w/sales/endpoints/e1");

    // Assert
    expect(await screen.findByRole("textbox", { name: "SQL" })).toHaveValue("select id from customers where tier = #{tier}");
    expect(screen.queryByRole("button", { name: "Publish" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Run" })).not.toBeInTheDocument();
  });

  it("publish is blocked until the draft is saved, and shows validation errors", async () => {
    // Arrange
    mockServer({
      ...common,
      "GET /admin/api/me": () => json(meAs("author")),
      "GET /admin/api/ws/sales/endpoints/e1": () => json(DETAIL),
      "POST /admin/api/ws/sales/endpoints/e1:validate": () =>
        json({ ok: false, findings: [{ severity: "error", code: "write_in_get", message: "A GET endpoint must not change data." }],
          detectedParameters: [], outputColumns: [] }),
    });
    const user = userEvent.setup();
    renderApp("/w/sales/endpoints/e1");

    // Act: an unsaved edit blocks publishing
    await user.type(await screen.findByRole("textbox", { name: "SQL" }), " ");
    expect(screen.getByRole("button", { name: "Publish" })).toBeDisabled();
    await user.clear(screen.getByRole("textbox", { name: "SQL" }));
    await user.type(screen.getByRole("textbox", { name: "SQL" }), "select id from customers where tier = #{{tier}");
    await user.click(screen.getByRole("button", { name: "Publish" }));

    // Assert
    const dialog = await screen.findByRole("dialog");
    expect(await within(dialog).findByText(/A GET endpoint must not change data/)).toBeInTheDocument();
    expect(within(dialog).getByRole("button", { name: "Publish" })).toBeDisabled();
  });

  it("preview sends the on-screen draft and test values, and shows rows", async () => {
    // Arrange
    const server = mockServer({
      ...common,
      "GET /admin/api/me": () => json(meAs("author")),
      "GET /admin/api/ws/sales/endpoints/e1": () => json(DETAIL),
      "POST /admin/api/ws/sales/endpoints/e1/preview": () =>
        json({ columns: [{ name: "id", type: "int4" }], rows: [{ id: 3 }, { id: 6 }], truncated: false, durationMs: 4,
          executed: [{ sql: "select id from customers where tier = ?", parameters: ["gold"] }], findings: [] }),
    });
    const user = userEvent.setup();
    renderApp("/w/sales/endpoints/e1");

    // Act
    await user.type(await screen.findByLabelText("tier"), "gold");
    await user.click(screen.getByRole("button", { name: "Run" }));

    // Assert
    expect(await screen.findByText("6")).toBeInTheDocument();
    const call = server.calls.find((c) => c.key === "POST /admin/api/ws/sales/endpoints/e1/preview");
    const body = JSON.parse(String(call?.init?.body));
    expect(body.params).toEqual({ tier: "gold" });
    expect(body.definition.task.statements[0].template).toContain("#{tier}");
  });

  it("Try it calls the live version as a service account and points to Access on a 403", async () => {
    // Arrange
    const server = mockServer({
      ...common,
      "GET /admin/api/me": () => json(meAs("author")),
      "GET /admin/api/ws/sales/endpoints/e1": () => json(DETAIL),
      "GET /admin/api/ws/sales/endpoints/e1/versions": () =>
        json([{ version: 2, live: true, note: null, publishedAt: "2026-11-17T00:00:00Z", publishedBy: "alice",
          method: "GET", path: "customers", access: "private", definition: DETAIL.definition }]),
      "GET /admin/api/ws/sales/service-accounts": () =>
        json([{ id: "sa1", name: "billing", disabled: false, createdAt: "2026-11-17T00:00:00Z", clientCount: 1, grantCount: 0 }]),
      "POST /admin/api/ws/sales/endpoints/e1/try": () =>
        json({ status: 403, durationMs: 5, method: "GET", url: "http://h/api/sales/customers?tier=gold", headers: {},
          body: { code: "not_granted" }, curl: "curl 'http://h/api/sales/customers?tier=gold' -H \"Authorization: Bearer $TOKEN\"",
          serviceAccountName: "billing" }),
      "GET /admin/api/ws/sales/endpoints/e1/access": () => json([]),
    });
    const user = userEvent.setup();
    renderApp("/w/sales/endpoints/e1/try");

    // Act
    expect(await screen.findByRole("button", { name: "Send" })).toBeDisabled();
    await user.click(screen.getByRole("combobox", { name: "Call as" }));
    await user.click(await screen.findByRole("option", { name: "billing" }));
    await user.type(screen.getByLabelText(/tier/), "gold");
    await user.click(screen.getByRole("button", { name: "Send" }));

    // Assert
    expect(await screen.findByText(/Authorization: Bearer ••••/)).toBeInTheDocument();
    const call = server.calls.find((c) => c.key === "POST /admin/api/ws/sales/endpoints/e1/try");
    expect(JSON.parse(String(call?.init?.body))).toEqual({ serviceAccountId: "sa1", params: { tier: "gold" }, page: null, size: null });
    await user.click(screen.getByRole("button", { name: "Grant access" }));
    await waitFor(() => expect(screen.getByTestId("location")).toHaveTextContent("/w/sales/endpoints/e1/access"));
  });

  it("opens a tab from its own URL", async () => {
    // Arrange
    mockServer({ ...common, "GET /admin/api/me": () => json(meAs("author")), "GET /admin/api/ws/sales/endpoints/e1": () => json(DETAIL) });

    // Act
    renderApp("/w/sales/endpoints/e1/settings");

    // Assert
    expect(await screen.findByLabelText("Allow streaming")).toBeInTheDocument();
    expect(screen.getByRole("tab", { name: "Settings" })).toHaveAttribute("aria-selected", "true");
  });

  it("saves parameter rules and the alert switch with the draft", async () => {
    // Arrange
    const server = mockServer({
      ...common,
      "GET /admin/api/me": () => json(meAs("author")),
      "GET /admin/api/ws/sales/endpoints/e1": () => json(DETAIL),
      "PATCH /admin/api/ws/sales/endpoints/e1": () => json(DETAIL),
    });
    const user = userEvent.setup();
    renderApp("/w/sales/endpoints/e1/settings");

    // Act
    await user.click(await screen.findByRole("button", { name: "Add rule" }));
    await user.type(screen.getByLabelText("Rule 1 condition"), "tier != 'x'");
    await user.type(screen.getByLabelText("Rule 1 message"), "tier x is retired.");
    await user.click(screen.getByLabelText("Alert on failures"));
    await user.click(screen.getByRole("button", { name: "Save" }));

    // Assert
    await waitFor(() => expect(server.calls.some((c) => c.key === "PATCH /admin/api/ws/sales/endpoints/e1")).toBe(true));
    const patch = server.calls.find((c) => c.key === "PATCH /admin/api/ws/sales/endpoints/e1")!;
    const sent = JSON.parse(String(patch.init?.body));
    expect(sent.definition.rules).toEqual([{ test: "tier != 'x'", message: "tier x is retired." }]);
    expect(sent.definition.alerts).toEqual({ enabled: true });
  });
});
