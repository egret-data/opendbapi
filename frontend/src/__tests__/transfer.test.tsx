import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

import type { ImportReport } from "@/api/generated/model";
import { ME_ALICE, json, mockServer, renderApp } from "./testServer";

afterEach(() => vi.unstubAllGlobals());

const asAdmin = { ...ME_ALICE, workspaces: [{ id: "w1", slug: "sales", name: "Sales", role: "admin" as const, configLocked: false }] };

const REPORT = (dryRun: boolean): ImportReport => ({
  dryRun, created: 1, skipped: 1,
  items: [
    { kind: "endpoint", name: "Users", result: "created", id: dryRun ? null : "e1",
      notes: ["Imported as a draft (GET /api/users, private).", "SQL 1 uses ${…} text substitution"] },
    { kind: "endpoint", name: "Search", result: "skipped", id: null, notes: ["Only SQL executors are imported; this API uses Elasticsearch."] },
  ],
});

describe("Import & export", () => {
  it("checks DBAPI files first, then imports them", async () => {
    // Arrange
    const server = mockServer({
      "GET /admin/api/me": () => json(asAdmin),
      "POST /admin/api/ws/sales/import/dbapi": (_init, url) => json(REPORT(url.includes("dryRun=true"))),
    });
    const user = userEvent.setup();
    renderApp("/w/sales/settings/transfer");
    const apis = new File([JSON.stringify([{ id: "a1", name: "Users", path: "api/users" }])], "api_config.json", { type: "application/json" });

    // Act
    expect(await screen.findByRole("link", { name: "Download YAML" })).toHaveAttribute("href", "/admin/api/ws/sales/export?format=yaml");
    expect(screen.getByRole("button", { name: "Import" })).toBeDisabled();
    await user.upload(screen.getByLabelText("APIs"), apis);
    await user.click(within(screen.getByRole("region", { name: "Import from DBAPI" })).getByRole("button", { name: "Check" }));
    const report = await screen.findByRole("table", { name: "Import report" });
    await user.click(screen.getByRole("button", { name: "Import" }));

    // Assert
    expect(within(report).getByText("Only SQL executors are imported; this API uses Elasticsearch.")).toBeInTheDocument();
    await waitFor(() => expect(screen.getByText("Created 1, skipped 1.")).toBeInTheDocument());
    const calls = server.calls.filter((c) => c.key === "POST /admin/api/ws/sales/import/dbapi");
    expect(calls).toHaveLength(2);
    expect(JSON.parse(String(calls[0].init?.body))).toEqual({ apis: [{ id: "a1", name: "Users", path: "api/users" }], groups: null, datasources: null });
  });

  it("is only for workspace admins", async () => {
    // Arrange
    mockServer({ "GET /admin/api/me": () => json({ ...ME_ALICE, workspaces: [{ id: "w1", slug: "sales", name: "Sales", role: "author", configLocked: false }] }) });

    // Act
    renderApp("/w/sales/settings/transfer");

    // Assert
    expect(await screen.findByText("Export and import are for workspace admins.")).toBeInTheDocument();
  });
});
