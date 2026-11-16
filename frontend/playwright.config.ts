import { defineConfig, devices } from "@playwright/test";

/**
 * End-to-end tests against a running stack: `deploy/dev` services up, the backend on
 * :8520 and the Vite dev server on :5173. Not part of `npm test`; run `npm run test:e2e`.
 */
export default defineConfig({
  testDir: "./tests/e2e",
  fullyParallel: false,
  workers: 1,
  timeout: 60_000,
  expect: { timeout: 10_000 },
  reporter: [["list"]],
  use: {
    baseURL: process.env.OPENDBAPI_E2E_BASE_URL ?? "http://localhost:5173",
    trace: "on-first-retry",
    screenshot: "only-on-failure",
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
});
