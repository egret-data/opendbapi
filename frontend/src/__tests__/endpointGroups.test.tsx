import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

import type { ApiGroup, EndpointSummary } from "@/api/generated/model";
import { ME_ALICE, json, mockServer, renderApp } from "./testServer";

afterEach(() => {
  vi.unstubAllGlobals();
  localStorage.clear();
});

const meAs = (role: "author" | "viewer") => ({ ...ME_ALICE, workspaces: [{ id: "w1", slug: "sales", name: "Sales", role }] });

const ep = (id: string, name: string, groupId: string | null): EndpointSummary => ({
  id, name, description: null, method: "GET", path: name.toLowerCase(), state: "live", liveVersion: 1, dirty: false,
  access: "private", groupId, datasourceId: "ds1", trashed: false, updatedAt: "2026-11-16T00:00:00Z", major: 1,
  deprecatedAt: null, sunsetAt: null,
});

const REPORTS: ApiGroup = { id: "g1", name: "Reports", endpointCount: 2, grantCount: 2 };
const SALES: ApiGroup = { id: "g2", name: "Sales data", endpointCount: 0, grantCount: 0 };

function server(role: "author" | "viewer" = "author", extra: Record<string, () => Response> = {}) {
  return mockServer({
    "GET /admin/api/me": () => json(meAs(role)),
    "GET /admin/api/system/info": () => json({ version: "dev", mode: "standalone", servingBaseUrl: "http://h/api" }),
    "GET /admin/api/ws/sales/datasources": () => json([{ id: "ds1", name: "crm" }]),
    "GET /admin/api/ws/sales/groups": () => json([REPORTS, SALES]),
    "GET /admin/api/ws/sales/endpoints": () =>
      json([ep("e1", "Orders", "g1"), ep("e2", "Revenue", "g1"), ep("e3", "Customers", null)]),
    ...extra,
  });
}

const heading = (name: RegExp) => screen.findByRole("button", { name });

describe("Endpoint groups", () => {
  it("lists endpoints under their group headings, with an empty group and No group", async () => {
    // Arrange
    server();

    // Act
    renderApp("/w/sales/endpoints");

    // Assert
    expect(await heading(/Reports \(2\)/)).toHaveAttribute("aria-expanded", "true");
    expect(await heading(/Sales data \(0\)/)).toBeInTheDocument();
    expect(await heading(/No group \(1\)/)).toBeInTheDocument();
    expect(screen.getByText(/Empty\. Drag endpoints here/)).toBeInTheDocument();
    expect(screen.getByText("groups")).toBeInTheDocument();
  });

  it("collapses a group and remembers it", async () => {
    // Arrange
    server();
    const user = userEvent.setup();
    const view = renderApp("/w/sales/endpoints");
    await screen.findByRole("link", { name: "Orders" });

    // Act
    await user.click(await heading(/Reports \(2\)/));

    // Assert
    expect(screen.queryByRole("link", { name: "Orders" })).not.toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Customers" })).toBeInTheDocument();
    view.unmount();
    renderApp("/w/sales/endpoints");
    expect(await heading(/Reports \(2\)/)).toHaveAttribute("aria-expanded", "false");
  });

  it("creates a group from the toolbar", async () => {
    // Arrange
    const s = server("author", { "POST /admin/api/ws/sales/groups": () => json({ id: "g3", name: "Billing", endpointCount: 0, grantCount: 0 }, 201) });
    const user = userEvent.setup();
    renderApp("/w/sales/endpoints");

    // Act
    await user.click(await screen.findByRole("button", { name: "New group" }));
    await user.type(screen.getByLabelText("Name"), "Billing");
    await user.click(screen.getByRole("button", { name: "Create" }));

    // Assert
    await waitFor(() => expect(s.calls.some((c) => c.key === "POST /admin/api/ws/sales/groups")).toBe(true));
    const sent = JSON.parse(String(s.calls.find((c) => c.key === "POST /admin/api/ws/sales/groups")!.init?.body));
    expect(sent).toEqual({ name: "Billing" });
  });

  it("moves an ungrouped endpoint into a group without grants at once", async () => {
    // Arrange
    const s = server("author", { "PUT /admin/api/ws/sales/endpoints/e3/group": () => json(ep("e3", "Customers", "g2")) });
    const user = userEvent.setup();
    renderApp("/w/sales/endpoints");

    // Act
    await user.click(await screen.findByRole("button", { name: "Actions for Customers" }));
    await user.click(await screen.findByRole("menuitem", { name: "Move to Sales data" }));

    // Assert
    await waitFor(() => expect(s.calls.some((c) => c.key === "PUT /admin/api/ws/sales/endpoints/e3/group")).toBe(true));
    const sent = JSON.parse(String(s.calls.find((c) => c.key === "PUT /admin/api/ws/sales/endpoints/e3/group")!.init?.body));
    expect(sent).toEqual({ groupId: "g2" });
  });

  it("asks before a move takes access away from service accounts granted the group", async () => {
    // Arrange
    const s = server("author", { "PUT /admin/api/ws/sales/endpoints/e1/group": () => json(ep("e1", "Orders", null)) });
    const user = userEvent.setup();
    renderApp("/w/sales/endpoints");

    // Act
    await user.click(await screen.findByRole("button", { name: "Actions for Orders" }));
    await user.click(await screen.findByRole("menuitem", { name: "Remove from group" }));

    // Assert
    const dialog = await screen.findByRole("alertdialog");
    expect(within(dialog).getByText("Service accounts granted Reports lose access (2).")).toBeInTheDocument();
    expect(s.calls.some((c) => c.key === "PUT /admin/api/ws/sales/endpoints/e1/group")).toBe(false);
    await user.click(within(dialog).getByRole("button", { name: "Move" }));
    await waitFor(() => expect(s.calls.some((c) => c.key === "PUT /admin/api/ws/sales/endpoints/e1/group")).toBe(true));
    const sent = JSON.parse(String(s.calls.find((c) => c.key === "PUT /admin/api/ws/sales/endpoints/e1/group")!.init?.body));
    expect(sent).toEqual({ groupId: null });
  });

  it("moves selected endpoints together", async () => {
    // Arrange
    const s = server("author", {
      "PUT /admin/api/ws/sales/endpoints/e1/group": () => json(ep("e1", "Orders", "g2")),
      "PUT /admin/api/ws/sales/endpoints/e3/group": () => json(ep("e3", "Customers", "g2")),
    });
    const user = userEvent.setup();
    renderApp("/w/sales/endpoints");

    // Act
    await user.click(await screen.findByRole("button", { name: "Select" }));
    await user.click(screen.getByRole("checkbox", { name: "Select Orders" }));
    await user.click(screen.getByRole("checkbox", { name: "Select Customers" }));
    await user.click(screen.getByRole("button", { name: "Move 2 to group…" }));
    await user.click(screen.getByRole("radio", { name: "Sales data" }));
    await user.click(screen.getByRole("button", { name: "Move" }));
    await user.click(within(await screen.findByRole("alertdialog")).getByRole("button", { name: "Move" }));

    // Assert
    await waitFor(() => {
      expect(s.calls.some((c) => c.key === "PUT /admin/api/ws/sales/endpoints/e1/group")).toBe(true);
      expect(s.calls.some((c) => c.key === "PUT /admin/api/ws/sales/endpoints/e3/group")).toBe(true);
    });
  });

  it("deleting a group warns that grants on it are revoked", async () => {
    // Arrange
    const s = server("author", { "DELETE /admin/api/ws/sales/groups/g1": () => new Response(null, { status: 204 }) });
    const user = userEvent.setup();
    renderApp("/w/sales/endpoints");

    // Act
    await user.click(await screen.findByRole("button", { name: "Actions for group Reports" }));
    await user.click(await screen.findByRole("menuitem", { name: "Delete group…" }));

    // Assert
    const dialog = await screen.findByRole("alertdialog");
    expect(within(dialog).getByText("Service accounts granted this group lose access to its endpoints (2).")).toBeInTheDocument();
    await user.click(within(dialog).getByRole("button", { name: "Delete group" }));
    await waitFor(() => expect(s.calls.some((c) => c.key === "DELETE /admin/api/ws/sales/groups/g1")).toBe(true));
  });

  it("viewers see the groups but cannot organize them", async () => {
    // Arrange
    server("viewer");

    // Act
    renderApp("/w/sales/endpoints");

    // Assert
    expect(await heading(/Reports \(2\)/)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "New group" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Actions for group Reports" })).not.toBeInTheDocument();
    expect(screen.getByText("Empty.")).toBeInTheDocument();
  });
});
