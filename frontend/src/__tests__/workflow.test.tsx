import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

import type { EndpointDetail } from "@/api/generated/model";
import { workflowSteps } from "@/routes/workspace/endpoints/WorkflowEditor";
import { ME_ALICE, json, mockServer, renderApp } from "./testServer";

vi.mock("@/components/monaco/MonacoEditor", () => ({
  default: ({ value }: { value: string }) => <textarea aria-label="code" readOnly value={value} />,
  MonacoDiffEditor: () => null,
}));

afterEach(() => vi.unstubAllGlobals());

const DETAIL: EndpointDetail = {
  summary: {
    id: "e1", name: "Profile", description: null, method: "GET", path: "profile", state: "draft", liveVersion: null, dirty: true,
    access: "public", groupId: null, datasourceId: "ds1", trashed: false, updatedAt: "2026-11-17T00:00:00Z",
    major: 1, deprecatedAt: null, sunsetAt: null,
  },
  createdAt: "2026-11-17T00:00:00Z",
  definition: {
    params: [],
    task: { type: "sql", datasourceId: "ds1", transaction: false, statements: [{ id: "main", template: "select 1 as id" }] },
    pagination: { enabled: false, defaultSize: 20, maxSize: 500, count: true },
    limits: { timeoutSeconds: 30, maxRows: 10000 },
    streaming: { allowed: false, formats: ["ndjson", "csv"] },
    rules: [],
    alerts: { enabled: false },
    output: { naming: "as_is", shape: "list", types: {}, nest: [] },
    cache: { enabled: false, ttlSeconds: 60, scope: "client" },
  },
};

describe("workflowSteps", () => {
  it("groups nodes that can run together and spots cycles", () => {
    // Arrange
    const node = (id: string, dependsOn: string[]) => ({ id, kind: "sql" as const, dependsOn });

    // Act
    const steps = workflowSteps([node("user", []), node("stats", []), node("orders", ["user"]), node("shape", ["orders", "stats"])]);
    const cycle = workflowSteps([node("a", ["b"]), node("b", ["a"])]);

    // Assert
    expect(steps).toEqual([["user", "stats"], ["orders"], ["shape"]]);
    expect(cycle).toBeNull();
  });
});

describe("Workflow editor", () => {
  it("turns the query into a workflow, adds a step after it and saves the graph", async () => {
    // Arrange
    const server = mockServer({
      "GET /admin/api/me": () => json({ ...ME_ALICE, workspaces: [{ id: "w1", slug: "sales", name: "Sales", role: "author" }] }),
      "GET /admin/api/system/info": () => json({ version: "dev", mode: "standalone", servingBaseUrl: "http://h/api" }),
      "GET /admin/api/ws/sales/datasources": () => json([{ id: "ds1", name: "crm" }]),
      "GET /admin/api/ws/sales/groups": () => json([]),
      "GET /admin/api/ws/sales/endpoints/e1": () => json(DETAIL),
      "PATCH /admin/api/ws/sales/endpoints/e1": () => json(DETAIL),
    });
    const user = userEvent.setup();
    renderApp("/w/sales/endpoints/e1");

    // Act
    await user.click(await screen.findByLabelText("Language"));
    await user.click(screen.getByRole("option", { name: "Workflow (several steps)" }));
    await user.click(screen.getByRole("button", { name: "Add node" }));
    const step2 = screen.getByRole("listitem", { name: "Node step2" });
    await user.clear(within(step2).getByLabelText("Only when (optional)"));
    await user.type(within(step2).getByLabelText("Only when (optional)"), "main != null");
    const summary = screen.getByText("main → step2");
    await user.click(screen.getByRole("button", { name: "Save" }));

    // Assert
    expect(summary).toBeInTheDocument();
    expect(within(step2).getByRole("checkbox", { name: "step2 runs after main" })).toBeChecked();
    await waitFor(() => expect(server.calls.some((c) => c.key === "PATCH /admin/api/ws/sales/endpoints/e1")).toBe(true));
    const sent = JSON.parse(String(server.calls.find((c) => c.key === "PATCH /admin/api/ws/sales/endpoints/e1")!.init?.body));
    expect(sent.definition.task.type).toBe("workflow");
    expect(sent.definition.task.nodes).toEqual([
      { id: "main", kind: "sql", template: "select 1 as id", source: null, dependsOn: [], when: null, result: "list" },
      { id: "step2", kind: "sql", template: "select 1", source: null, dependsOn: ["main"], when: "main != null", result: "list" },
    ]);
  });
});
