import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";
import path from "node:path";

// Vite 8's native config loader has no CJS `__dirname`.
const here = import.meta.dirname;
const backend = process.env.OPENDBAPI_BACKEND_URL ?? "http://localhost:8520";

export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: { alias: [{ find: "@", replacement: path.resolve(here, "src") }] },
  server: {
    // The SPA talks only to the management API; the serving surface and OAuth2
    // token endpoint are proxied so Try-it's copied curl works against the dev server too.
    proxy: {
      "/admin/api": { target: backend, changeOrigin: true },
      "/oauth2": { target: backend, changeOrigin: true },
      "/login/oauth2": { target: backend, changeOrigin: true },
      "/api": { target: backend, changeOrigin: true },
    },
  },
});
