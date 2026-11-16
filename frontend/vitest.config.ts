import { defineConfig, configDefaults } from "vitest/config";
import react from "@vitejs/plugin-react";
import path from "node:path";

const here = import.meta.dirname;

export default defineConfig({
  plugins: [react()],
  resolve: { alias: [{ find: "@", replacement: path.resolve(here, "src") }] },
  test: {
    environment: "jsdom",
    globals: true,
    setupFiles: ["./vitest.setup.ts"],
    // Playwright specs run via `npm run test:e2e`, not Vitest.
    exclude: [...configDefaults.exclude, "tests/e2e/**"],
  },
});
