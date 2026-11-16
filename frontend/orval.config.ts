import { defineConfig } from "orval";

/**
 * Frontend client codegen from the backend's committed management API spec
 * (`../openapi.json`, kept in sync by the backend's ApplicationIntegrationTest).
 * One file per OpenAPI tag; react-query hooks; every request goes through
 * `src/api/mutator.ts#customInstance` (credentials, CSRF, RFC 9457 errors).
 * operationIds are globally unique in the backend, so hooks are named after them
 * (`listWorkspaces` → `useListWorkspaces`) and resolve to the response body itself.
 */

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
        fetch: { includeHttpResponseReturnType: false },
      },
    },
  },
});
