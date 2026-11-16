import { defineConfig } from "orval";

/**
 * Frontend client codegen from the backend's committed management API spec
 * (`../openapi.json`, kept in sync by the backend's ApplicationIntegrationTest).
 * One file per OpenAPI tag; react-query hooks; every request goes through
 * `src/api/mutator.ts#customInstance` (credentials, CSRF, RFC 9457 errors).
 */
function toCamel(input: string): string {
  return input
    .replace(/[^a-zA-Z0-9]+(.)?/g, (_m, c: string | undefined) => (c ? c.toUpperCase() : ""))
    .replace(/^(.)/, (m) => m.toLowerCase());
}

export default defineConfig({
  opendbapi: {
    input: { target: "../openapi.json" },
    output: {
      mode: "tags-split",
      target: "src/api/generated",
      schemas: "src/api/generated/model",
      client: "react-query",
      prettier: false,
      override: {
        mutator: { path: "src/api/mutator.ts", name: "customInstance" },
        // Tag-prefix operations so short operationIds (`list`, `create`) stay unique.
        operationName: (operation) => toCamel(`${operation.tags?.[0] ?? "api"}-${operation.operationId ?? "op"}`),
      },
    },
  },
});
