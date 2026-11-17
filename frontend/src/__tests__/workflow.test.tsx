import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

import type { EndpointDetail } from "@/api/generated/model";
import { workflowSteps } from "@/routes/workspace/endpoints/WorkflowEditor";
import { connect, disconnect, edgesOf, rename, type WfNode } from "@/routes/workspace/endpoints/workflowGraph";
import { ME_ALICE, json, mockServer, renderApp } from "./testServer";

vi.mock("@/components/monaco/MonacoEditor", () => ({
  default: ({ value }: { value: string }) => <textarea aria-label="code" readOnly value={value} />,
  MonacoDiffEditor: () => null,
}));

afterEach(() => {
  vi.unstubAllGlobals();
  localStorage.clear();
});

const server = () => mockServer({
  "GET /admin/api/me": () => json({ ...ME_ALICE, workspaces: [{ id: "w1", slug: "sales", name: "Sales", role: "author" }] }),
  "GET /admin/api/system/info": () => json({ version: "dev", mode: "standalone", servingBaseUrl: "http://h/api" }),
  "GET /admin/api/ws/sales/datasources": () => json([{ id: "ds1", name: "crm" }]),
  "GET /admin/api/ws/sales/groups": () => json([]),
  "GET /admin/api/ws/sales/endpoints": () => json([]),
  "GET /admin/api/ws/sales/endpoints/e1": () => json(DETAIL),
  "PATCH /admin/api/ws/sales/endpoints/e1": () => json(DETAIL),
});

const sent = (calls: { key: string; init?: RequestInit }[]) =>
  JSON.parse(String(calls.find((c) => c.key === "PATCH /admin/api/ws/sales/endpoints/e1")!.init?.body));

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

describe("workflow graph", () => {
  const n = (id: string, kind: WfNode["kind"], dependsOn: string[] = []): WfNode => ({ id, kind, dependsOn });

  it("connects decision branches, refuses loops, and renames references", () => {
    // Arrange
    const nodes = [n("count", "api"), n("isNew", "decision", ["count"]), n("add", "api"), n("done", "end")];

    // Act
    const branched = connect(nodes, "isNew", "add", "yes");
    const looped = connect(branched, "add", "count", null);
    const fromEnd = connect(branched, "done", "add", null);
    const renamed = rename(branched, "isNew", "fresh");
    const cut = disconnect(branched, "isNew:yes->add");

    // Assert
    expect(branched.find((x) => x.id === "add")!.dependsOn).toEqual(["isNew:yes"]);
    expect(edgesOf(branched)).toContainEqual({ id: "isNew:yes->add", source: "isNew", target: "add", sourceHandle: "yes" });
    expect(looped).toBe(branched);
    expect(fromEnd).toBe(branched);
    expect(renamed.find((x) => x.id === "add")!.dependsOn).toEqual(["fresh:yes"]);
    expect(cut.find((x) => x.id === "add")!.dependsOn).toEqual([]);
  });
});

describe("Workflow editor", () => {
  it("turns the query into a workflow, adds a step after it and saves the graph", async () => {
    // Arrange
    const api = server();
    const user = userEvent.setup();
    renderApp("/w/sales/endpoints/e1");

    // Act
    await user.click(await screen.findByLabelText("Language"));
    await user.click(screen.getByRole("option", { name: "Workflow (several steps)" }));
    await user.click(screen.getByRole("tab", { name: "List" }));
    await user.click(screen.getByRole("button", { name: "Add node" }));
    const step2 = screen.getByRole("listitem", { name: "Node step2" });
    await user.clear(within(step2).getByLabelText("Only when (optional)"));
    await user.type(within(step2).getByLabelText("Only when (optional)"), "main != null");
    const summary = screen.getByText("main → step2");
    await user.click(screen.getByRole("button", { name: "Save" }));

    // Assert
    expect(summary).toBeInTheDocument();
    expect(within(step2).getByRole("checkbox", { name: "step2 runs after main" })).toBeChecked();
    await waitFor(() => expect(api.calls.some((c) => c.key === "PATCH /admin/api/ws/sales/endpoints/e1")).toBe(true));
    const body = sent(api.calls);
    expect(body.definition.task.type).toBe("workflow");
    expect(body.definition.task.nodes[0]).toMatchObject({ id: "main", kind: "sql", template: "select 1 as id", dependsOn: [] });
    expect(body.definition.task.nodes[1]).toMatchObject({ id: "step2", kind: "sql", template: "select 1", dependsOn: ["main"], when: "main != null" });
  });

  it("the canvas adds a decision node and edits it beside the drawing", async () => {
    // Arrange
    const api = server();
    const user = userEvent.setup();
    renderApp("/w/sales/endpoints/e1");

    // Act
    await user.click(await screen.findByLabelText("Language"));
    await user.click(screen.getByRole("option", { name: "Workflow (several steps)" }));
    await user.click(within(screen.getByRole("toolbar", { name: "Add a node" })).getByRole("button", { name: "Decision" }));
    const panel = screen.getByRole("complementary", { name: "Selected node" });
    const condition = within(panel).getByLabelText("Condition (yes when it holds)");
    await user.clear(condition);
    await user.type(condition, "main[[0].id == 1");
    await user.click(screen.getByRole("button", { name: "Save" }));

    // Assert
    expect(within(screen.getByLabelText("Workflow canvas")).getByText("check2")).toBeInTheDocument();
    await waitFor(() => expect(api.calls.some((c) => c.key === "PATCH /admin/api/ws/sales/endpoints/e1")).toBe(true));
    expect(sent(api.calls).definition.task.nodes[1]).toMatchObject({ id: "check2", kind: "decision", condition: "main[0].id == 1" });
  });

  it("a node after a decision follows one branch", async () => {
    // Arrange
    const api = server();
    const user = userEvent.setup();
    renderApp("/w/sales/endpoints/e1");
    await user.click(await screen.findByLabelText("Language"));
    await user.click(screen.getByRole("option", { name: "Workflow (several steps)" }));
    await user.click(screen.getByRole("tab", { name: "List" }));

    // Act
    const main = screen.getByRole("listitem", { name: "Node main" });
    await user.click(within(main).getByLabelText("Kind"));
    await user.click(screen.getByRole("option", { name: "Decision" }));
    await user.click(screen.getByRole("button", { name: "Add node" }));
    const step2 = screen.getByRole("listitem", { name: "Node step2" });
    await user.click(within(step2).getByRole("combobox", { name: "Branch of main" }));
    await user.click(screen.getByRole("option", { name: "No" }));
    await user.click(screen.getByRole("button", { name: "Save" }));

    // Assert
    await waitFor(() => expect(api.calls.some((c) => c.key === "PATCH /admin/api/ws/sales/endpoints/e1")).toBe(true));
    const nodes = sent(api.calls).definition.task.nodes;
    expect(nodes[0]).toMatchObject({ id: "main", kind: "decision", condition: "true" });
    expect(nodes[1]).toMatchObject({ id: "step2", dependsOn: ["main:no"] });
  });
});
