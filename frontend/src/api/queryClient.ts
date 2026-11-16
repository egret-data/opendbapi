import { QueryClient } from "@tanstack/react-query";

/** The single app-wide QueryClient. */
export const queryClient = new QueryClient({
  defaultOptions: { queries: { refetchOnWindowFocus: false, retry: 1, staleTime: 30_000 } },
});

/**
 * Drops every cached query except `["me"]`. Call it whenever the signed-in identity
 * changes in this tab (login, logout), so no page can render the previous user's data.
 */
export function clearUserScopedCache(qc: QueryClient) {
  qc.removeQueries({ predicate: (query) => query.queryKey[0] !== "me" });
}
