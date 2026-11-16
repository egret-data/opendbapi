import { API_BASE, apiFetch } from "./client";

/**
 * Orval custom mutator. Generated hooks call `customInstance<T>(url, init)` with the
 * spec's absolute path (`/admin/api/...`) and get the response body back as `T`.
 * The request itself goes through {@link apiFetch}, so generated and hand-written calls
 * share credentials, CSRF and error handling; `apiFetch` throws on non-2xx.
 */

export const customInstance = async <T>(url: string, options: RequestInit = {}): Promise<T> => {
  const path = url.startsWith(API_BASE) ? url.slice(API_BASE.length) : url;
  // Orval stringifies JSON bodies but may omit the header; a bare string body would
  // otherwise go out as text/plain and be refused.
  const headers = new Headers(options.headers);
  if (typeof options.body === "string" && !headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json");
  }

  const res = await apiFetch(path, { ...options, headers });

  if (res.status === 204 || res.headers.get("content-length") === "0") return undefined as T;
  const contentType = res.headers.get("content-type") ?? "";
  return (contentType.includes("json") ? await res.json() : await res.text()) as T;
};

export default customInstance;
