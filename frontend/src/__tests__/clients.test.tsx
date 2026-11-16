import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";

import type { ServiceAccountDetail } from "@/api/generated/model";
import { ME_ALICE, json, mockServer, renderApp } from "./testServer";

const meAs = (role: "admin" | "author") => ({ ...ME_ALICE, workspaces: [{ id: "w1", slug: "sales", name: "Sales", role }] });

const DETAIL: ServiceAccountDetail = {
  summary: { id: "sa1", name: "billing", description: null, disabled: false, createdAt: "2026-11-17T00:00:00Z",
    clientCount: 1, grantCount: 1, lastUsedAt: null },
  clients: [{ id: "c1", clientId: "odc_abc", createdAt: "2026-11-17T00:00:00Z", expiresAt: null, lastUsedAt: null, revokedAt: null }],
  grants: [{ resourceType: "endpoint", resourceId: "e1", resourceName: "Customers", detail: "GET /customers", grantedAt: "2026-11-17T00:00:00Z" }],
};

const common = {
  "GET /admin/api/system/info": () =>
    json({ version: "dev", mode: "standalone", servingBaseUrl: "http://h/api", tokenUrl: "http://h/oauth2/token" }),
  "GET /admin/api/ws/sales/endpoints": () => json([]),
  "GET /admin/api/ws/sales/groups": () => json([]),
};

describe("Clients", () => {
  it("lists service accounts; authors see them but cannot create", async () => {
    // Arrange
    mockServer({ ...common, "GET /admin/api/me": () => json(meAs("author")),
      "GET /admin/api/ws/sales/service-accounts": () => json([DETAIL.summary]) });

    // Act
    renderApp("/w/sales/clients");

    // Assert
    const row = (await screen.findByRole("link", { name: "billing" })).closest("tr")!;
    expect(within(row).getByText("Active")).toBeInTheDocument();
    expect(within(row).getByText("Never")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "New service account" })).not.toBeInTheDocument();
  });

  it("shows a new client's secret once, with a token example", async () => {
    // Arrange
    const server = mockServer({
      ...common,
      "GET /admin/api/me": () => json(meAs("admin")),
      "GET /admin/api/ws/sales/service-accounts/sa1": () => json(DETAIL),
      "POST /admin/api/ws/sales/service-accounts/sa1/clients": () =>
        json({ client: { ...DETAIL.clients[0], id: "c2", clientId: "odc_new" }, clientSecret: "ods_s3cret" }, 201),
    });
    const user = userEvent.setup();
    renderApp("/w/sales/clients/sa1");

    // Act
    await user.click(await screen.findByRole("button", { name: "New client" }));
    await user.type(screen.getByLabelText(/Expires after/), "30");
    await user.click(screen.getByRole("button", { name: "Create client" }));

    // Assert
    const dialog = await screen.findByRole("dialog", { name: "Client created" });
    expect(within(dialog).getByText("ods_s3cret")).toBeInTheDocument();
    expect(within(dialog).getByText(/curl -s -u 'odc_new:ods_s3cret'/)).toBeInTheDocument();
    const post = server.calls.find((c) => c.key === "POST /admin/api/ws/sales/service-accounts/sa1/clients");
    expect(JSON.parse(String(post?.init?.body))).toEqual({ expiresInDays: 30 });

    // Act + Assert: Escape does not close it; only the explicit button does
    await user.keyboard("{Escape}");
    expect(screen.getByRole("dialog", { name: "Client created" })).toBeInTheDocument();

    // Act: closing forgets the secret
    await user.click(within(dialog).getByRole("button", { name: "I have stored the secret" }));
    await waitFor(() => expect(screen.queryByText("ods_s3cret")).not.toBeInTheDocument());
  });

  it("removes a grant", async () => {
    // Arrange
    const server = mockServer({
      ...common,
      "GET /admin/api/me": () => json(meAs("admin")),
      "GET /admin/api/ws/sales/service-accounts/sa1": () => json(DETAIL),
      "DELETE /admin/api/ws/sales/service-accounts/sa1/grants/endpoint/e1": () => new Response(null, { status: 204 }),
    });
    const user = userEvent.setup();
    renderApp("/w/sales/clients/sa1");

    // Act
    await user.click(await screen.findByRole("button", { name: "Remove grant on Customers" }));

    // Assert
    await waitFor(() =>
      expect(server.calls.some((c) => c.key === "DELETE /admin/api/ws/sales/service-accounts/sa1/grants/endpoint/e1")).toBe(true));
  });

  it("rotates a client's secret after confirmation and shows the new one once", async () => {
    // Arrange
    mockServer({
      ...common,
      "GET /admin/api/me": () => json(meAs("admin")),
      "GET /admin/api/ws/sales/service-accounts/sa1": () => json(DETAIL),
      "POST /admin/api/ws/sales/service-accounts/sa1/clients/c1:rotate": () =>
        json({ client: DETAIL.clients[0], clientSecret: "ods_rotated" }),
    });
    const user = userEvent.setup();
    renderApp("/w/sales/clients/sa1");

    // Act
    await user.click(await screen.findByRole("button", { name: "Rotate secret" }));
    await user.click(within(await screen.findByRole("alertdialog")).getByRole("button", { name: "Rotate" }));

    // Assert
    const dialog = await screen.findByRole("dialog", { name: "Secret rotated" });
    expect(within(dialog).getByText("ods_rotated")).toBeInTheDocument();
    expect(within(dialog).getByText(/old secret no longer works/)).toBeInTheDocument();
  });
});
