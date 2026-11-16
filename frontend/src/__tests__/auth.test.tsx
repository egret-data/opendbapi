import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

import { ME_ALICE, PROVIDERS, json, mockServer, problem, renderApp } from "./testServer";

afterEach(() => vi.unstubAllGlobals());

describe("RequireAuth", () => {
  it("sends a signed-out visitor to /login and remembers where they were going", async () => {
    // Arrange
    mockServer({
      "GET /admin/api/me": () => problem(401, "Not signed in"),
      "GET /admin/api/auth/providers": () => json(PROVIDERS),
    });

    // Act
    renderApp("/w/sales/datasources");

    // Assert
    await waitFor(() =>
      expect(screen.getByTestId("location")).toHaveTextContent("/login?next=%2Fw%2Fsales%2Fdatasources"),
    );
  });

  it("shows a server failure as an error with a retry, not as signed out", async () => {
    // Arrange
    mockServer({ "GET /admin/api/me": () => problem(503, "Database unavailable") });

    // Act
    renderApp("/");

    // Assert
    expect(await screen.findByText(/Could not reach the server: Database unavailable/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Try again" })).toBeInTheDocument();
    expect(screen.getByTestId("location")).toHaveTextContent(/^\/$/);
  });
});

describe("LoginPage", () => {
  it("offers SSO, local and each LDAP directory", async () => {
    // Arrange
    mockServer({
      "GET /admin/api/me": () => problem(401, "Not signed in"),
      "GET /admin/api/auth/providers": () => json(PROVIDERS),
    });

    // Act
    renderApp("/login");

    // Assert
    expect(await screen.findByRole("link", { name: "Sign in with Keycloak" })).toHaveAttribute(
      "href",
      "/admin/api/auth/oidc/kc/start",
    );
    expect(screen.getByRole("radio", { name: "Local account" })).toBeInTheDocument();
    expect(screen.getByRole("radio", { name: "Corp AD" })).toBeInTheDocument();
  });

  it("signs in against the chosen LDAP directory and returns to the requested page", async () => {
    // Arrange
    let signedIn = false;
    const server = mockServer({
      "GET /admin/api/me": () => (signedIn ? json(ME_ALICE) : problem(401, "Not signed in")),
      "GET /admin/api/auth/providers": () => json(PROVIDERS),
      "POST /admin/api/auth/login": () => {
        signedIn = true;
        return new Response(null, { status: 204 });
      },
    });
    const user = userEvent.setup();
    renderApp("/login?next=%2Fw%2Ffinance%2Factivity");

    // Act
    await user.click(await screen.findByRole("radio", { name: "Corp AD" }));
    await user.type(screen.getByLabelText("Username"), "alice");
    await user.type(screen.getByLabelText("Password"), "s3cret");
    await user.click(screen.getByRole("button", { name: "Sign in" }));

    // Assert
    await waitFor(() => expect(screen.getByTestId("location")).toHaveTextContent("/w/finance/activity"));
    const login = server.calls.find((c) => c.key === "POST /admin/api/auth/login");
    expect(JSON.parse(String(login?.init?.body))).toEqual({
      source: "ldap",
      idpId: "ldap-1",
      username: "alice",
      password: "s3cret",
    });
  });

  it("never follows an off-site next= after sign-in", async () => {
    // Arrange
    let signedIn = false;
    mockServer({
      "GET /admin/api/me": () => (signedIn ? json(ME_ALICE) : problem(401, "Not signed in")),
      "GET /admin/api/auth/providers": () => json({ ...PROVIDERS, ldap: [], oidc: [] }),
      "POST /admin/api/auth/login": () => {
        signedIn = true;
        return new Response(null, { status: 204 });
      },
    });
    const user = userEvent.setup();
    renderApp("/login?next=%2F%2Fevil.example.com%2Fphish");

    // Act
    await user.type(await screen.findByLabelText("Username"), "alice");
    await user.type(screen.getByLabelText("Password"), "s3cret");
    await user.click(screen.getByRole("button", { name: "Sign in" }));

    // Assert: lands in the app (home redirect), not on the other host's path
    await waitFor(() => expect(screen.getByTestId("location")).toHaveTextContent("/w/sales/endpoints"));
  });

  it("does not reveal whether the username exists", async () => {
    // Arrange
    mockServer({
      "GET /admin/api/me": () => problem(401, "Not signed in"),
      "GET /admin/api/auth/providers": () => json({ ...PROVIDERS, ldap: [], oidc: [] }),
      "POST /admin/api/auth/login": () => problem(401, "User 'bob' not found"),
    });
    const user = userEvent.setup();
    renderApp("/login");

    // Act
    await user.type(await screen.findByLabelText("Username"), "bob");
    await user.type(screen.getByLabelText("Password"), "x");
    await user.click(screen.getByRole("button", { name: "Sign in" }));

    // Assert
    expect(await screen.findByRole("alert")).toHaveTextContent("Invalid username or password.");
    expect(screen.queryByText(/not found/)).not.toBeInTheDocument();
  });
});
