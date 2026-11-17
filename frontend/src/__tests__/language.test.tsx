import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

import i18n, { setLanguage } from "@/i18n";
import { ME_ALICE, json, mockServer, renderApp } from "./testServer";

afterEach(() => {
  setLanguage("en");
  vi.unstubAllGlobals();
});

describe("Language", () => {
  it("switches the interface to Vietnamese and remembers the choice", async () => {
    // Arrange
    mockServer({ "GET /admin/api/me": () => json(ME_ALICE), "GET /admin/api/ws/sales/endpoints": () => json([]) });
    const user = userEvent.setup();
    renderApp("/w/sales/endpoints");

    // Act
    await user.click(await screen.findByRole("button", { name: "User menu" }));
    await user.click(screen.getByRole("menuitem", { name: "Tiếng Việt" }));
    await user.click(screen.getByRole("button", { name: "Menu người dùng" }));

    // Assert
    expect(screen.getByRole("menuitem", { name: "Đăng xuất" })).toBeInTheDocument();
    expect(i18n.language).toBe("vi");
    expect(localStorage.getItem("opendbapi.language")).toBe("vi");
    expect(document.documentElement.lang).toBe("vi");
  });
});
