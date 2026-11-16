import { screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { ME_ALICE, json, mockServer, renderApp } from "./testServer";

afterEach(() => vi.unstubAllGlobals());

describe("home and workspace routing", () => {
  it("opens the first workspace's endpoints", async () => {
    // Arrange
    mockServer({ "GET /admin/api/me": () => json(ME_ALICE) });

    // Act
    renderApp("/");

    // Assert
    await waitFor(() => expect(screen.getByTestId("location")).toHaveTextContent("/w/sales/endpoints"));
    expect(await screen.findByRole("heading", { name: "Endpoints" })).toBeInTheDocument();
  });

  it("explains a missing membership instead of an empty shell", async () => {
    // Arrange
    mockServer({ "GET /admin/api/me": () => json({ ...ME_ALICE, workspaces: [] }) });

    // Act
    renderApp("/");

    // Assert
    expect(await screen.findByRole("heading", { name: "No workspaces yet" })).toBeInTheDocument();
  });

  it("refuses a workspace the user is not a member of", async () => {
    // Arrange
    mockServer({ "GET /admin/api/me": () => json(ME_ALICE) });

    // Act
    renderApp("/w/hr/endpoints");

    // Assert
    expect(await screen.findByRole("heading", { name: "You don't have access" })).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "Endpoints" })).not.toBeInTheDocument();
  });

  it("refuses administration to a non-admin and hides its navigation", async () => {
    // Arrange
    mockServer({ "GET /admin/api/me": () => json(ME_ALICE) });

    // Act
    renderApp("/admin/users");

    // Assert
    expect(await screen.findByRole("heading", { name: "You don't have access" })).toBeInTheDocument();
    const nav = screen.getByRole("navigation", { name: "Main" });
    expect(within(nav).queryByRole("link", { name: "Users" })).not.toBeInTheDocument();
  });

  it("shows administration to a system admin", async () => {
    // Arrange
    mockServer({ "GET /admin/api/me": () => json({ ...ME_ALICE, systemAdmin: true }) });

    // Act
    renderApp("/w/sales/endpoints");

    // Assert
    const nav = await screen.findByRole("navigation", { name: "Main" });
    expect(within(nav).getByRole("link", { name: "Identity providers" })).toHaveAttribute(
      "href",
      "/admin/identity-providers",
    );
    expect(within(nav).getByRole("link", { name: "Datasources" })).toHaveAttribute("href", "/w/sales/datasources");
  });
});
