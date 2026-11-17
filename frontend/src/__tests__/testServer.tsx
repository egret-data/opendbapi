import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render } from "@testing-library/react";
import { MemoryRouter, useLocation } from "react-router-dom";
import { vi } from "vitest";

import { AppRoutes } from "@/App";
import type { AuthProviders, Me } from "@/api/auth";

type Handler = (init: RequestInit | undefined, url: string) => Response | Promise<Response>;

export const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

export const problem = (status: number, detail: string, code?: string) =>
  new Response(JSON.stringify({ type: "about:blank", title: "Error", status, detail, code }), {
    status,
    headers: { "Content-Type": "application/problem+json" },
  });

/** Stubs `fetch` with a table of `"METHOD /admin/api/path"` handlers; anything else is a 404. */
export function mockServer(routes: Record<string, Handler>) {
  const calls: { key: string; init?: RequestInit; url: string }[] = [];
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = typeof input === "string" ? input : input.toString();
    const key = `${(init?.method ?? "GET").toUpperCase()} ${url.split("?")[0]}`;
    calls.push({ key, init, url });
    const handler = routes[key];
    return handler ? handler(init, url) : problem(404, `No mock for ${key}`);
  });
  vi.stubGlobal("fetch", fetchMock);
  return { calls };
}

export const ME_ALICE: Me = {
  id: "u1",
  username: "alice",
  displayName: "Alice Nguyen",
  email: "alice@example.com",
  source: "ldap",
  systemAdmin: false,
  workspaces: [
    { id: "w1", slug: "sales", name: "Sales", role: "author" },
    { id: "w2", slug: "finance", name: "Finance", role: "viewer" },
  ],
};

export const PROVIDERS: AuthProviders = {
  local: true,
  ldap: [{ id: "ldap-1", name: "Corp AD" }],
  oidc: [{ id: "kc", name: "Keycloak" }],
};

function LocationProbe() {
  const location = useLocation();
  return <output data-testid="location">{location.pathname + location.search}</output>;
}

export function renderApp(path: string) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={[path]}>
        <AppRoutes />
        <LocationProbe />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}
