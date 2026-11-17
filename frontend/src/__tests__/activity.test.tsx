import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";

import { ME_ALICE, json, mockServer, renderApp } from "./testServer";

const me = { ...ME_ALICE, workspaces: [{ id: "w1", slug: "sales", name: "Sales", role: "viewer", configLocked: false }] };
const SUMMARY = {
  requests: 200, errors: 10, p95Ms: 42.4, activeClients: 3,
  byDay: [{ day: "2026-11-16", requests: 120, errors: 4 }, { day: "2026-11-17", requests: 80, errors: 6 }],
  topEndpoints: [{ endpointId: "e1", name: "Customers", method: "GET", path: "/customers", requests: 150, errors: 2, p95Ms: 30 }],
  topErrors: [{ code: "query_failed", count: 6 }],
  slowestEndpoints: [{ endpointId: "e2", name: "Big report", method: "GET", path: "/report", requests: 9, errors: 0, p95Ms: 812 }],
  topClients: [{ clientId: "odc_abc", serviceAccount: "billing", requests: 140, errors: 3, p95Ms: 25 }],
  topIps: [{ ip: "10.0.0.1", requests: 190, errors: 9 }],
};
const LOG = [{ id: "l1", ts: "2026-11-17T01:00:00Z", endpointId: "e1", endpointName: "Customers", version: 2, method: "GET",
  path: "/customers", status: 500, durationMs: 12, rows: 0, clientId: "odc_abc", ip: "10.0.0.1", errorCode: "query_failed",
  error: "division by zero", traceId: null, requestId: "req-1", tryIt: false }];

describe("Activity", () => {
  it("shows totals, the daily chart as a table, top endpoints and calls with their errors", async () => {
    // Arrange
    mockServer({
      "GET /admin/api/me": () => json(me),
      "GET /admin/api/ws/sales/activity/summary": () => json(SUMMARY),
      "GET /admin/api/ws/sales/activity/log": () => json(LOG),
      "GET /admin/api/ws/sales/endpoints": () => json([]),
    });

    // Act
    renderApp("/w/sales/activity");

    // Assert
    expect(await screen.findByText("5.0%")).toBeInTheDocument();
    expect(screen.getByText("42 ms")).toBeInTheDocument();
    const day = screen.getByRole("table", { name: "Requests per day" });
    expect(within(day).getByText("120")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Customers" })).toHaveAttribute("href", "/w/sales/endpoints/e1");
    const slowest = screen.getByRole("region", { name: "Slowest endpoints (p95, 5+ calls)" });
    expect(within(slowest).getByText("812 ms")).toBeInTheDocument();
    const clients = screen.getByRole("region", { name: "Top clients" });
    expect(within(clients).getByText("billing")).toBeInTheDocument();
    expect(within(clients).getByText("140 · 3 err")).toBeInTheDocument();
    expect(within(screen.getByRole("region", { name: "Top addresses" })).getByText("10.0.0.1")).toBeInTheDocument();
    expect(await screen.findByText("division by zero")).toBeInTheDocument();
  });

  it("asks for Try-it calls and filters by request id", async () => {
    // Arrange
    const server = mockServer({
      "GET /admin/api/me": () => json(me),
      "GET /admin/api/ws/sales/activity/summary": () => json(SUMMARY),
      "GET /admin/api/ws/sales/activity/log": () => json([]),
      "GET /admin/api/ws/sales/endpoints": () => json([]),
    });
    const user = userEvent.setup();
    renderApp("/w/sales/activity");

    // Act
    await user.click(await screen.findByLabelText("Include Try-it"));
    await user.type(screen.getByLabelText("Request id"), "req-9");

    // Assert
    await waitFor(() => {
      const fetchMock = globalThis.fetch as unknown as { mock: { calls: [string][] } };
      const urls = fetchMock.mock.calls.map((c) => String(c[0]));
      expect(urls.some((u) => u.includes("/activity/summary") && u.includes("includeTryIt=true"))).toBe(true);
      expect(urls.some((u) => u.includes("/activity/log") && u.includes("requestId=req-9"))).toBe(true);
    });
    expect(server.calls.length).toBeGreaterThan(0);
  });
});
