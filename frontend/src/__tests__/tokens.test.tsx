import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

import { ME_ALICE, json, mockServer, renderApp } from "./testServer";

afterEach(() => vi.unstubAllGlobals());

const me = { ...ME_ALICE, workspaces: [{ id: "w1", slug: "sales", name: "Sales", role: "admin" as const, configLocked: false }] };
const TOKEN = { id: "t1", name: "CI deploy", prefix: "odb_AbCdEf", workspace: "sales", createdAt: "2026-11-17T00:00:00Z",
  expiresAt: "2027-02-15T00:00:00Z", lastUsedAt: null };

describe("API tokens", () => {
  it("creates a workspace token, shows its secret once and lists tokens without secrets", async () => {
    // Arrange
    const server = mockServer({
      "GET /admin/api/me": () => json(me),
      "GET /admin/api/me/tokens": () => json([TOKEN]),
      "POST /admin/api/me/tokens": () => json({ token: TOKEN, secret: "odb_AbCdEf-secret" }, 201),
      "DELETE /admin/api/me/tokens/t1": () => new Response(null, { status: 204 }),
    });
    const user = userEvent.setup();
    renderApp("/account/tokens");

    // Act
    const row = (await screen.findByText("CI deploy")).closest("tr")!;
    await user.click(screen.getByRole("button", { name: "New token" }));
    const dialog = screen.getByRole("dialog", { name: "New API token" });
    await user.type(within(dialog).getByLabelText("Name"), "CI deploy");
    await user.click(within(dialog).getByLabelText("Workspace"));
    await user.click(screen.getByRole("option", { name: "Sales (sales)" }));
    await user.click(within(dialog).getByRole("button", { name: "Create token" }));
    const secret = await screen.findByLabelText("Token");
    await user.click(screen.getByRole("button", { name: "Done" }));
    await user.click(within(row).getByRole("button", { name: "Revoke CI deploy" }));

    // Assert
    expect(within(row).getByText("odb_AbCdEf…")).toBeInTheDocument();
    expect(secret).toHaveValue("odb_AbCdEf-secret");
    const post = server.calls.find((c) => c.key === "POST /admin/api/me/tokens")!;
    expect(JSON.parse(String(post.init?.body))).toEqual({ name: "CI deploy", workspace: "sales", expiresInDays: 90 });
    await waitFor(() => expect(server.calls.some((c) => c.key === "DELETE /admin/api/me/tokens/t1")).toBe(true));
  });
});
