import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

import type { ConfigApplyResult, ConfigPlan } from "@/api/generated/model";
import { ME_ALICE, json, mockServer, renderApp } from "./testServer";

afterEach(() => vi.unstubAllGlobals());

const asAdmin = { ...ME_ALICE, workspaces: [{ id: "w1", slug: "sales", name: "Sales", role: "admin" as const, configLocked: false }] };
const PLAN: ConfigPlan = {
  environment: "prod", prune: false, publish: true, digest: "abc123",
  counts: { create: 1, skip: 1, unchanged: 2 },
  changes: [
    { kind: "Datasource", name: "meta", action: "skip", details: ["bound to datasource 'prod-db' in environment prod"] },
    { kind: "Endpoint", name: "GET /numbers", action: "create", details: ["publish"] },
    { kind: "Group", name: "sales", action: "unchanged", details: [] },
    { kind: "ServiceAccount", name: "reporter", action: "unchanged", details: [] },
  ],
};
const APPLIED: ConfigApplyResult = { plan: PLAN, published: [{ endpoint: "GET /numbers", result: "published", message: null }] };
const YAML = "apiVersion: opendbapi/v1\nkind: Group\nmetadata: {name: sales}\n";

describe("Apply YAML", () => {
  it("checks a file against an environment, then applies exactly that plan", async () => {
    // Arrange
    const server = mockServer({
      "GET /admin/api/me": () => json(asAdmin),
      "GET /admin/api/ws/sales/environments": () => json([{ name: "prod", bindings: { meta: "prod-db" }, updatedAt: "2026-11-17T00:00:00Z" }]),
      "POST /admin/api/ws/sales/config:plan": () => json(PLAN),
      "POST /admin/api/ws/sales/config:apply": () => json(APPLIED),
    });
    const user = userEvent.setup();
    renderApp("/w/sales/settings/transfer");

    // Act
    await user.upload(await screen.findByLabelText("YAML file"), new File([YAML], "api.yaml", { type: "application/yaml" }));
    await user.click(screen.getByLabelText("Environment"));
    await user.click(await screen.findByRole("option", { name: "prod" }));
    const section = screen.getByRole("region", { name: "Apply YAML" });
    const apply = within(section).getByRole("button", { name: "Apply" });
    const disabledBeforeCheck = apply.hasAttribute("disabled");
    await user.click(within(section).getByRole("button", { name: "Check" }));
    const table = await screen.findByRole("table", { name: "Planned changes" });
    const planned = within(table).getAllByRole("row").map((r) => r.textContent);
    const summary = screen.queryByText("1 to create, 0 to update, 0 to delete, 2 unchanged.");
    await user.click(apply);

    // Assert
    expect(disabledBeforeCheck).toBe(true);
    expect(planned.some((r) => r?.includes("GET /numbers"))).toBe(true);
    expect(planned.some((r) => r?.includes("reporter"))).toBe(false);
    expect(summary).not.toBeNull();
    await waitFor(() => expect(server.calls.map((c) => c.key)).toContain("POST /admin/api/ws/sales/config:apply"));
    expect(await within(section).findByText(/^published$/i)).toBeInTheDocument();
    const plan = server.calls.find((c) => c.key === "POST /admin/api/ws/sales/config:plan")!;
    expect(plan.init?.body).toBe(YAML);
    expect(plan.url).toContain("environment=prod");
    await waitFor(() => expect(server.calls.some((c) => c.key === "POST /admin/api/ws/sales/config:apply")).toBe(true));
    const applied = server.calls.find((c) => c.key === "POST /admin/api/ws/sales/config:apply")!;
    expect(applied.url).toContain("expectedDigest=abc123");
    expect(applied.url).toContain("publish=true");
  });
});
