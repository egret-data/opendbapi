import { screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { ME_ALICE, json, mockServer, renderApp } from "./testServer";

afterEach(() => vi.unstubAllGlobals());

describe("Config lock", () => {
  it("tells members the workspace changes through configuration files and hides New endpoint", async () => {
    // Arrange
    mockServer({
      "GET /admin/api/me": () => json({ ...ME_ALICE, workspaces: [{ id: "w1", slug: "sales", name: "Sales", role: "author", configLocked: true }] }),
      "GET /admin/api/ws/sales/endpoints": () => json([]),
      "GET /admin/api/ws/sales/groups": () => json([]),
      "GET /admin/api/ws/sales/datasources": () => json([]),
    });

    // Act
    renderApp("/w/sales/endpoints");

    // Assert
    expect(await screen.findByText(/managed by configuration files/)).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "New endpoint" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "New endpoint" })).not.toBeInTheDocument();
  });
});
