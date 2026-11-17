import { expect, type Page } from "@playwright/test";

/** Where the stack under test lives; defaults match `deploy/dev` (see README). */
export const env = {
  adminUser: process.env.OPENDBAPI_E2E_ADMIN_USER ?? "admin",
  adminPassword: process.env.OPENDBAPI_E2E_ADMIN_PASSWORD ?? "",
  dsHost: process.env.OPENDBAPI_E2E_DS_HOST ?? "localhost",
  dsPort: process.env.OPENDBAPI_E2E_DS_PORT ?? "55432",
  dsDatabase: process.env.OPENDBAPI_E2E_DS_DATABASE ?? "opendbapi",
  dsUser: process.env.OPENDBAPI_E2E_DS_USER ?? "postgres",
  dsPassword: process.env.OPENDBAPI_E2E_DS_PASSWORD ?? "postgres",
};

export async function signIn(page: Page) {
  if (!env.adminPassword) throw new Error("Set OPENDBAPI_E2E_ADMIN_PASSWORD to the system admin's password");
  await page.goto("/login");
  await page.getByLabel("Username").fill(env.adminUser);
  await page.getByLabel("Password").fill(env.adminPassword);
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page).not.toHaveURL(/\/login/);
}

/** Creates a workspace with a unique slug through the admin API, using the signed-in session. */
export async function newWorkspace(page: Page): Promise<string> {
  const slug = `e2e-${Date.now().toString(36)}`;
  const xsrf = (await page.context().cookies()).find((c) => c.name === "XSRF-TOKEN")?.value ?? "";
  const res = await page.request.post("/admin/api/workspaces", {
    data: { slug, name: `E2E ${slug}` },
    headers: { "X-XSRF-TOKEN": xsrf },
  });
  expect(res.status(), await res.text()).toBe(201);
  return slug;
}
