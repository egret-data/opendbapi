import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

import type { ProviderView } from "@/api/generated/model";
import { formFrom, toRequest } from "@/routes/admin/providerForm";
import { slugify } from "@/routes/admin/WorkspacesPage";
import { ME_ALICE, json, mockServer, problem, renderApp } from "./testServer";

afterEach(() => vi.unstubAllGlobals());

const ADMIN = { ...ME_ALICE, systemAdmin: true };

describe("slugify", () => {
  it("suggests a URL-safe slug, Vietnamese included", () => {
    expect(slugify("Phòng Kinh doanh – Miền Bắc")).toBe("phong-kinh-doanh-mien-bac");
    expect(slugify("Đà Nẵng")).toBe("da-nang");
    expect(slugify("  --Sales  ")).toBe("sales");
  });
});

describe("identity provider form", () => {
  const ldap: ProviderView = {
    id: "p1",
    type: "ldap",
    name: "Corp",
    enabled: true,
    config: { url: "ldaps://ad:636", userSearchFilter: "(uid={0})", allowInsecure: true },
    hasSecret: true,
    secretEnv: null,
    createdAt: "2026-11-16T00:00:00Z",
    updatedAt: "2026-11-16T00:00:00Z",
  };

  it("round-trips settings and keeps the stored secret when the field is empty", () => {
    // Act
    const request = toRequest(formFrom(ldap));

    // Assert
    expect(request.secret).toBeNull();
    expect(request.config).toEqual({ url: "ldaps://ad:636", userSearchFilter: "(uid={0})", allowInsecure: true });
  });

  it("splits OIDC scopes and drops empty values", () => {
    // Arrange
    const form = formFrom({ ...ldap, type: "oidc", config: { issuerUri: "https://sso", clientId: "c" } });
    form.values.scopes = "openid,  groups , ";
    form.values.groupsClaim = "  ";

    // Act
    const request = toRequest(form);

    // Assert
    expect(request.config).toEqual({ issuerUri: "https://sso", clientId: "c", scopes: ["openid", "groups"] });
  });
});

describe("Users", () => {
  it("creates a local user and shows the server's refusal verbatim", async () => {
    // Arrange
    const server = mockServer({
      "GET /admin/api/me": () => json(ADMIN),
      "GET /admin/api/users": () => json([]),
      "POST /admin/api/users": () => problem(409, "A local user named 'carol' already exists.", "username_taken"),
    });
    const user = userEvent.setup();
    renderApp("/admin/users");

    // Act
    await user.click(await screen.findByRole("button", { name: "New local user" }));
    await user.type(screen.getByLabelText("Username"), "carol");
    await user.type(screen.getByLabelText("Initial password"), "a-long-password!");
    await user.click(screen.getByLabelText("System admin"));
    await user.click(screen.getByRole("button", { name: "Create user" }));

    // Assert
    expect(await screen.findByRole("alert")).toHaveTextContent("A local user named 'carol' already exists.");
    const post = server.calls.find((c) => c.key === "POST /admin/api/users");
    expect(JSON.parse(String(post?.init?.body))).toEqual({
      username: "carol",
      displayName: null,
      email: null,
      password: "a-long-password!",
      systemAdmin: true,
    });
    expect(screen.getByLabelText("Username")).toHaveValue("carol");
  });
});

describe("Workspaces", () => {
  it("creates a workspace with a suggested slug", async () => {
    // Arrange
    const server = mockServer({
      "GET /admin/api/me": () => json(ADMIN),
      "GET /admin/api/workspaces": () => json([]),
      "GET /admin/api/users": () => json([]),
      "POST /admin/api/workspaces": () =>
        json({ id: "w9", slug: "data-team", name: "Data Team", status: "active", quotas: {}, archivedAt: null, createdAt: "2026-11-16T00:00:00Z" }, 201),
    });
    const user = userEvent.setup();
    renderApp("/admin/workspaces");

    // Act
    await user.click((await screen.findAllByRole("button", { name: "New workspace" }))[0]);
    await user.type(screen.getByLabelText("Name"), "Data Team");
    await user.click(screen.getByRole("button", { name: "Create workspace" }));

    // Assert
    await waitFor(() => expect(server.calls.some((c) => c.key === "POST /admin/api/workspaces")).toBe(true));
    const post = server.calls.find((c) => c.key === "POST /admin/api/workspaces");
    expect(JSON.parse(String(post?.init?.body))).toEqual({ name: "Data Team", slug: "data-team", ownerUserId: null });
  });

  it("is refused to non-admins", async () => {
    // Arrange
    mockServer({ "GET /admin/api/me": () => json(ME_ALICE) });

    // Act
    renderApp("/admin/workspaces");

    // Assert
    expect(await screen.findByRole("heading", { name: "You don't have access" })).toBeInTheDocument();
  });
});
