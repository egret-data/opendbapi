import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

import type { MemberView } from "@/api/generated/model";
import { ME_ALICE, json, mockServer, problem, renderApp } from "./testServer";

afterEach(() => vi.unstubAllGlobals());

const member = (over: Partial<MemberView>): MemberView => ({
  userId: "u-x",
  username: "x",
  displayName: null,
  email: null,
  source: "local",
  role: "author",
  fromMapping: false,
  createdAt: "2026-11-16T00:00:00Z",
  ...over,
});

const MEMBERS = [
  member({ userId: "u1", username: "alice", displayName: "Alice Nguyen", role: "admin" }),
  member({ userId: "u-owner", username: "olga", role: "owner" }),
  member({ userId: "u-bob", username: "bob", role: "author" }),
  member({ userId: "u-dave", username: "dave", source: "oidc", role: "viewer", fromMapping: true }),
];

function server(role: "admin" | "viewer") {
  const me = { ...ME_ALICE, workspaces: [{ id: "w1", slug: "sales", name: "Sales", role }] };
  return mockServer({
    "GET /admin/api/me": () => json(me),
    "GET /admin/api/ws/sales/members": () => json(MEMBERS),
    "PATCH /admin/api/ws/sales/members/u-bob": () => json({ ...MEMBERS[2], role: "viewer" }),
    "PATCH /admin/api/ws/sales/members/u-owner": () => problem(403, "Only an owner can grant or change the owner role."),
  });
}

describe("Members", () => {
  it("lets an admin change an author's role but not an owner's or a mapped one", async () => {
    // Arrange
    const calls = server("admin");
    const user = userEvent.setup();
    renderApp("/w/sales/settings/members");

    // Act
    await user.click(await screen.findByRole("combobox", { name: "Role of bob" }));
    await user.click(screen.getByRole("option", { name: "Viewer" }));

    // Assert
    await waitFor(() => expect(calls.calls.some((c) => c.key === "PATCH /admin/api/ws/sales/members/u-bob")).toBe(true));
    const patch = calls.calls.find((c) => c.key === "PATCH /admin/api/ws/sales/members/u-bob");
    expect(JSON.parse(String(patch?.init?.body))).toEqual({ role: "viewer" });
    expect(screen.queryByRole("combobox", { name: "Role of olga" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Why the role of olga is locked" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Why the role of dave is locked" })).toBeInTheDocument();
  });

  it("an admin is not offered the owner role", async () => {
    // Arrange
    server("admin");
    const user = userEvent.setup();
    renderApp("/w/sales/settings/members");

    // Act
    await user.click(await screen.findByRole("combobox", { name: "Role of bob" }));

    // Assert
    const options = within(screen.getByRole("listbox")).getAllByRole("option").map((o) => o.textContent);
    expect(options).not.toContain("Owner");
    expect(options).toContain("Admin");
  });

  it("a viewer sees members read-only, with the reason", async () => {
    // Arrange
    server("viewer");

    // Act
    renderApp("/w/sales/settings/members");

    // Assert
    expect(await screen.findByText("Alice Nguyen")).toBeInTheDocument();
    expect(screen.queryAllByRole("combobox", { name: /Role of/ })).toHaveLength(0);
    expect(screen.queryByRole("button", { name: "Add member" })).not.toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "Audit log" })).not.toBeInTheDocument();
  });

  it("shows a failed load as an error, not as an empty list", async () => {
    // Arrange
    mockServer({
      "GET /admin/api/me": () => json(ME_ALICE),
      "GET /admin/api/ws/sales/members": () => problem(500, "Database unavailable"),
    });

    // Act
    renderApp("/w/sales/settings/members");

    // Assert
    expect(await screen.findByText(/Could not load members: Database unavailable/)).toBeInTheDocument();
    expect(screen.queryByText("No members")).not.toBeInTheDocument();
  });
});

describe("Members: last owner", () => {
  it("locks the only owner's role and removal, saying why", async () => {
    // Arrange
    const me = { ...ME_ALICE, workspaces: [{ id: "w1", slug: "sales", name: "Sales", role: "owner" as const }] };
    mockServer({
      "GET /admin/api/me": () => json(me),
      "GET /admin/api/ws/sales/members": () =>
        json([member({ userId: "u1", username: "alice", role: "owner" }), member({ userId: "u-bob", username: "bob" })]),
    });

    // Act
    renderApp("/w/sales/settings/members");

    // Assert
    expect(await screen.findByRole("button", { name: "Why the role of alice is locked" })).toBeInTheDocument();
    expect(screen.queryByRole("combobox", { name: "Role of alice" })).not.toBeInTheDocument();
    expect(screen.getAllByRole("button", { name: "Remove" })).toHaveLength(1);
  });
});
