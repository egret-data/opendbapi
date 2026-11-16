import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import { ApiError, UnauthorizedError, apiFetch, apiJson } from "./client";
import { clearUserScopedCache } from "./queryClient";

/**
 * Session and sign-in (spec §21.1). Hand-written until the backend's auth endpoints
 * land (roadmap step 2); then these become thin wrappers over the generated hooks.
 */
export type WorkspaceRole = "owner" | "admin" | "script_author" | "author" | "viewer";

export interface MyWorkspace {
  id: string;
  slug: string;
  name: string;
  role: WorkspaceRole;
}

export interface Me {
  id: string;
  username: string;
  displayName: string | null;
  email: string | null;
  source: "local" | "ldap" | "oidc";
  systemAdmin: boolean;
  workspaces: MyWorkspace[];
}

export interface AuthProviders {
  local: boolean;
  ldap: { id: string; name: string }[];
  oidc: { id: string; name: string }[];
}

export type LoginSource = { kind: "local" } | { kind: "ldap"; idpId: string };

export const authKeys = {
  me: ["me"] as const,
  providers: ["auth-providers"] as const,
};

/** `null` when signed out; a 401 is an answer here, not an error. */
export function useMe() {
  return useQuery({
    queryKey: authKeys.me,
    queryFn: async (): Promise<Me | null> => {
      try {
        return await apiJson<Me>("/me");
      } catch (err) {
        if (err instanceof UnauthorizedError) return null;
        throw err;
      }
    },
    staleTime: 60_000,
  });
}

export function useAuthProviders() {
  return useQuery({
    queryKey: authKeys.providers,
    queryFn: () => apiJson<AuthProviders>("/auth/providers"),
    staleTime: Infinity,
  });
}

export function useLogin() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (input: { source: LoginSource; username: string; password: string }) => {
      const body =
        input.source.kind === "ldap"
          ? { source: "ldap", idpId: input.source.idpId, username: input.username, password: input.password }
          : { source: "local", username: input.username, password: input.password };
      try {
        await apiFetch("/auth/login", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(body),
        });
      } catch (err) {
        // A wrong password is a 401 from the login endpoint, not a lost session.
        if (err instanceof UnauthorizedError) throw new ApiError(401, "Invalid username or password.");
        throw err;
      }
    },
    onSuccess: async () => {
      clearUserScopedCache(qc);
      await qc.invalidateQueries({ queryKey: authKeys.me });
    },
  });
}

export function useLogout() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => apiFetch("/auth/logout", { method: "POST" }),
    onSettled: () => {
      clearUserScopedCache(qc);
      qc.setQueryData(authKeys.me, null);
    },
  });
}

/** Where an SSO button sends the browser; the backend answers with the IdP redirect. */
export function oidcStartUrl(idpId: string): string {
  return `/admin/api/auth/oidc/${encodeURIComponent(idpId)}/start`;
}
