import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

import type { ManagedSecret } from "@/api/generated/model";
import { ME_ALICE, json, mockServer, renderApp } from "./testServer";

afterEach(() => vi.unstubAllGlobals());

const asAdmin = { ...ME_ALICE, workspaces: [{ id: "w1", slug: "sales", name: "Sales", role: "admin" as const }] };
const USED: ManagedSecret = {
  name: "crm-db-password", description: "CRM replica", createdAt: "2026-11-17T00:00:00Z", updatedAt: "2026-11-17T00:00:00Z",
  usedBy: [{ kind: "datasource", id: "d1", name: "crm" }],
};

describe("Secrets", () => {
  it("lists secrets with their users and adds one without ever showing a value", async () => {
    // Arrange
    const server = mockServer({
      "GET /admin/api/me": () => json(asAdmin),
      "GET /admin/api/ws/sales/secrets": () => json([USED]),
      "POST /admin/api/ws/sales/secrets": () => json({ ...USED, name: "hook", usedBy: [] }, 201),
    });
    const user = userEvent.setup();
    renderApp("/w/sales/settings/secrets");

    // Act
    const row = (await screen.findByText("secret:crm-db-password")).closest("tr")!;
    await user.click(screen.getByRole("button", { name: "Add secret" }));
    const dialog = screen.getByRole("dialog", { name: "New secret" });
    await user.type(within(dialog).getByLabelText("Name"), "hook");
    await user.type(within(dialog).getByLabelText("Value"), "https://hooks.example/abc");
    const valueType = within(dialog).getByLabelText("Value").getAttribute("type");
    await user.click(within(dialog).getByRole("button", { name: "Save" }));

    // Assert
    expect(within(row).getByText("Datasource crm")).toBeInTheDocument();
    expect(within(row).getByRole("button", { name: "Delete secret crm-db-password" })).toBeDisabled();
    await waitFor(() => expect(server.calls.some((c) => c.key === "POST /admin/api/ws/sales/secrets")).toBe(true));
    const post = server.calls.find((c) => c.key === "POST /admin/api/ws/sales/secrets")!;
    expect(JSON.parse(String(post.init?.body))).toEqual({ name: "hook", description: null, value: "https://hooks.example/abc" });
    expect(valueType).toBe("password");
  });
});
