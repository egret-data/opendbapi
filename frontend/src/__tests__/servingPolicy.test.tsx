import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";

import { ME_ALICE, json, mockServer, renderApp } from "./testServer";

const meAs = (role: "admin" | "viewer") => ({ ...ME_ALICE, workspaces: [{ id: "w1", slug: "sales", name: "Sales", role }] });
const POLICY = { firewall: { mode: "off", entries: [] }, rateLimits: { perClientPerMinute: null, perIpPerMinute: null } };

describe("Firewall & limits", () => {
  it("saves a deny list and an IP rate limit", async () => {
    // Arrange
    const server = mockServer({
      "GET /admin/api/me": () => json(meAs("admin")),
      "GET /admin/api/ws/sales/serving-policy": () => json(POLICY),
      "PUT /admin/api/ws/sales/serving-policy": (init) => json(JSON.parse(String(init?.body))),
    });
    const user = userEvent.setup();
    renderApp("/w/sales/settings/serving");

    // Act
    await user.click(await screen.findByLabelText("Deny list"));
    await user.type(screen.getByLabelText("Addresses"), "10.0.0.0/8\n203.0.113.7");
    await user.type(screen.getByLabelText("Per IP address"), "120");
    await user.click(screen.getByRole("button", { name: "Save" }));

    // Assert
    await waitFor(() => expect(server.calls.some((c) => c.key === "PUT /admin/api/ws/sales/serving-policy")).toBe(true));
    const put = server.calls.find((c) => c.key === "PUT /admin/api/ws/sales/serving-policy");
    expect(JSON.parse(String(put?.init?.body))).toEqual({
      firewall: { mode: "deny", entries: ["10.0.0.0/8", "203.0.113.7"] },
      rateLimits: { perClientPerMinute: null, perIpPerMinute: 120 },
    });
  });

  it("is read-only for viewers", async () => {
    // Arrange
    mockServer({ "GET /admin/api/me": () => json(meAs("viewer")), "GET /admin/api/ws/sales/serving-policy": () => json(POLICY) });

    // Act
    renderApp("/w/sales/settings/serving");

    // Assert
    expect(await screen.findByLabelText("Per client")).toBeDisabled();
    expect(screen.queryByRole("button", { name: "Save" })).not.toBeInTheDocument();
  });
});
