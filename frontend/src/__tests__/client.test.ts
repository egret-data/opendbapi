import { afterEach, describe, expect, it, vi } from "vitest";

import { ApiError, UnauthorizedError, apiFetch } from "@/api/client";

afterEach(() => {
  vi.unstubAllGlobals();
  document.cookie = "XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT";
});

describe("apiFetch", () => {
  it("surfaces an RFC 9457 problem's detail and code", async () => {
    // Arrange
    vi.stubGlobal(
      "fetch",
      vi.fn(async () =>
        new Response(JSON.stringify({ status: 403, detail: "Service account 'bi' is not granted.", code: "not_granted" }), {
          status: 403,
          headers: { "Content-Type": "application/problem+json" },
        }),
      ),
    );

    // Act
    const err = await apiFetch("/x").catch((e: unknown) => e);

    // Assert
    expect(err).toBeInstanceOf(ApiError);
    expect(err).toMatchObject({ status: 403, code: "not_granted", message: "Service account 'bi' is not granted." });
  });

  it("maps 401 to UnauthorizedError", async () => {
    // Arrange
    vi.stubGlobal("fetch", vi.fn(async () => new Response(null, { status: 401 })));

    // Act + Assert
    await expect(apiFetch("/me")).rejects.toBeInstanceOf(UnauthorizedError);
  });

  it("echoes the CSRF cookie on mutating requests only", async () => {
    // Arrange
    document.cookie = "XSRF-TOKEN=tok123";
    const fetchMock = vi.fn(async (_url: string, _init?: RequestInit) => new Response(null, { status: 204 }));
    vi.stubGlobal("fetch", fetchMock);

    // Act
    await apiFetch("/things", { method: "POST" });
    await apiFetch("/things");

    // Assert
    const headersOf = (i: number) => new Headers(fetchMock.mock.calls[i][1]?.headers);
    expect(fetchMock.mock.calls[0][0]).toBe("/admin/api/things");
    expect(headersOf(0).get("X-XSRF-TOKEN")).toBe("tok123");
    expect(headersOf(1).get("X-XSRF-TOKEN")).toBeNull();
  });
});
