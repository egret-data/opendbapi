import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

import type { AlertChannel } from "@/api/generated/model";
import { ME_ALICE, json, mockServer, renderApp } from "./testServer";

afterEach(() => vi.unstubAllGlobals());

const meAs = (role: "admin" | "viewer") => ({ ...ME_ALICE, workspaces: [{ id: "w1", slug: "sales", name: "Sales", role }] });

const BROKEN: AlertChannel = {
  id: "c1", name: "oncall", kind: "teams", urlHost: "example.webhook.office.com", enabled: true,
  lastSentAt: "2026-11-17T00:00:00Z", lastError: "The webhook answered HTTP 404.", createdAt: "2026-11-17T00:00:00Z",
};

describe("Alert channels", () => {
  it("shows delivery problems and adds a Slack channel", async () => {
    // Arrange
    const server = mockServer({
      "GET /admin/api/me": () => json(meAs("admin")),
      "GET /admin/api/ws/sales/alert-channels": () => json([BROKEN]),
      "POST /admin/api/ws/sales/alert-channels": () => json({ ...BROKEN, id: "c2" }, 201),
    });
    const user = userEvent.setup();
    renderApp("/w/sales/settings/alerts");

    // Act
    const row = (await screen.findByText("oncall")).closest("tr")!;
    await user.click(screen.getByRole("button", { name: "Add channel" }));
    const dialog = screen.getByRole("dialog", { name: "New alert channel" });
    await user.type(within(dialog).getByLabelText("Name"), "data");
    await user.type(within(dialog).getByLabelText("Webhook URL"), "https://hooks.slack.com/services/T/B/X");
    await user.click(within(dialog).getByRole("button", { name: "Save" }));

    // Assert
    expect(within(row).getByText("The webhook answered HTTP 404.")).toBeInTheDocument();
    expect(within(row).getByText("example.webhook.office.com")).toBeInTheDocument();
    await waitFor(() => expect(server.calls.some((c) => c.key === "POST /admin/api/ws/sales/alert-channels")).toBe(true));
    const post = server.calls.find((c) => c.key === "POST /admin/api/ws/sales/alert-channels")!;
    expect(JSON.parse(String(post.init?.body))).toEqual({
      name: "data", kind: "slack", url: "https://hooks.slack.com/services/T/B/X", enabled: true,
    });
  });

  it("viewers see channels but cannot change or test them", async () => {
    // Arrange
    mockServer({
      "GET /admin/api/me": () => json(meAs("viewer")),
      "GET /admin/api/ws/sales/alert-channels": () => json([BROKEN]),
    });

    // Act
    renderApp("/w/sales/settings/alerts");

    // Assert
    expect(await screen.findByText("oncall")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Add channel" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Test" })).not.toBeInTheDocument();
  });
});
