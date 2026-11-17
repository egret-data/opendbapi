import { expect, test, type Page } from "@playwright/test";

import { env, newWorkspace, signIn } from "./support";

/**
 * The main author flow from spec §24: datasource → endpoint → preview → publish →
 * service account + client → grant → Try-it → Activity. Each run uses a fresh workspace,
 * so it can repeat against the same stack.
 */
test("an author publishes a private endpoint and a granted service account calls it", async ({ page }) => {
  // Arrange
  await signIn(page);
  const ws = await newWorkspace(page);

  // Act + Assert: connect a datasource
  await page.goto(`/w/${ws}/datasources/new`);
  await page.getByLabel("Name", { exact: true }).fill("Warehouse");
  await page.getByLabel("Host").fill(env.dsHost);
  await page.getByLabel("Port").fill(env.dsPort);
  await page.getByLabel("Database", { exact: true }).fill(env.dsDatabase);
  await page.getByLabel("Username").fill(env.dsUser);
  await page.getByLabel("Password").fill(env.dsPassword);
  await page.getByRole("button", { name: "Test connection" }).click();
  await expect(page.getByText(/connected/i).first()).toBeVisible();
  await page.getByRole("button", { name: "Create datasource" }).click();
  await expect(page.getByRole("heading", { name: "Warehouse" })).toBeVisible();

  // Act + Assert: create a private endpoint, write its SQL and preview it
  await page.goto(`/w/${ws}/endpoints/new`);
  await page.getByLabel("Name", { exact: true }).fill("Numbers");
  await page.getByLabel("Path").fill("v1/numbers");
  await page.getByLabel("Datasource").click();
  await page.getByRole("option", { name: "Warehouse" }).click();
  await page.getByRole("button", { name: "Create and edit query" }).click();
  await expect(page.getByRole("heading", { name: "Numbers" })).toBeVisible();

  await typeSql(page, "select g as n from generate_series(1, 3) g order by g");
  await page.getByRole("button", { name: "Run" }).click();
  await expect(page.getByText("3 rows")).toBeVisible();
  await page.getByRole("button", { name: "Save" }).click();
  await expect(page.getByRole("button", { name: "Save" })).toBeDisabled();

  // Act + Assert: publish version 1
  await page.getByRole("button", { name: "Publish" }).click();
  const publish = page.getByRole("dialog", { name: "Publish a new version" });
  await publish.getByLabel("Note (optional)").fill("First version");
  await publish.getByRole("button", { name: "Publish" }).click();
  await expect(page.getByText("Version 1 is live.")).toBeVisible();
  const endpointUrl = page.url().replace(/\/(query|try|settings|access|versions)$/, "");

  // Act + Assert: a service account with a client and a grant on the endpoint
  await page.goto(`/w/${ws}/clients`);
  await page.getByRole("button", { name: "New service account" }).first().click();
  const newAccount = page.getByRole("dialog", { name: "New service account" });
  await newAccount.getByLabel("Name").fill("billing-job");
  await newAccount.getByRole("button", { name: "Create" }).click();
  await expect(page.getByRole("heading", { name: "billing-job" })).toBeVisible();

  await page.getByRole("button", { name: "New client" }).click();
  await page.getByRole("dialog", { name: "New client" }).getByRole("button", { name: "Create client" }).click();
  const secret = page.getByRole("dialog", { name: "Client created" });
  const clientId = (await secret.locator("code").filter({ hasText: /^odc_/ }).textContent()) ?? "";
  const clientSecret = (await secret.locator("code").filter({ hasText: /^ods_/ }).textContent()) ?? "";
  expect(clientId).not.toBe("");
  expect(clientSecret).not.toBe("");
  await secret.getByRole("button", { name: "I have stored the secret" }).click();

  await page.getByRole("button", { name: "Grant access" }).click();
  const grant = page.getByRole("dialog", { name: "Grant access" });
  await grant.getByLabel("Endpoint").click();
  await page.getByRole("option", { name: /Numbers/ }).click();
  await grant.getByRole("button", { name: "Grant" }).click();
  await expect(page.getByRole("button", { name: "Remove grant on Numbers" })).toBeVisible();

  // Act + Assert: Try-it as that service account goes through the real serving pipeline
  await page.goto(`${endpointUrl}/try`);
  await page.getByLabel("Call as").click();
  await page.getByRole("option", { name: "billing-job" }).click();
  await page.getByRole("button", { name: "Send" }).click();
  const response = page.getByRole("region", { name: "Response" });
  await expect(response.getByText("200").first()).toBeVisible();
  await expect(response.getByText(/"n": 3/)).toBeVisible();

  // Act + Assert: the system holding the client exchanges it for a token and calls the endpoint
  const token = await page.request.post("/oauth2/token", {
    form: { grant_type: "client_credentials", client_id: clientId, client_secret: clientSecret },
  });
  expect(token.status(), await token.text()).toBe(200);
  const { access_token: accessToken } = (await token.json()) as { access_token: string };
  const call = await page.request.get(`/api/${ws}/v1/numbers`, { headers: { Authorization: `Bearer ${accessToken}` } });
  expect(call.status(), await call.text()).toBe(200);
  expect(((await call.json()) as { data: { n: number }[] }).data.map((r) => r.n)).toEqual([1, 2, 3]);
  const anonymous = await page.request.get(`/api/${ws}/v1/numbers`);
  expect(anonymous.status()).toBe(401);

  // Act + Assert: both calls show up in Activity; the access log is written a moment later
  await expect(async () => {
    await page.goto(`/w/${ws}/activity`);
    const calls = page.getByRole("region", { name: "Calls" });
    await expect(calls.getByRole("row").filter({ hasText: "200" }).filter({ hasText: clientId })).toBeVisible({ timeout: 2_000 });
    await expect(calls.getByRole("row").filter({ hasText: "401" })).toBeVisible({ timeout: 2_000 });
  }).toPass({ timeout: 20_000 });
});

/** Replaces the Monaco editor's content; inserting text avoids its auto-closing brackets. */
async function typeSql(page: Page, sql: string) {
  const lines = page.getByLabel("SQL template").locator(".view-lines");
  await lines.click();
  // Select everything with plain keys: headless Chromium on macOS does not deliver Meta+A to Monaco.
  for (let i = 0; i < 50; i++) await page.keyboard.press("ArrowUp");
  await page.keyboard.press("Home");
  for (let i = 0; i < 50; i++) await page.keyboard.press("Shift+ArrowDown");
  await page.keyboard.press("Shift+End");
  await page.keyboard.press("Delete");
  await page.keyboard.insertText(sql);
}
