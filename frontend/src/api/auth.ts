import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import { ApiError, UnauthorizedError } from "./client";
import { getAuthProviders, login, logout } from "./generated/auth/auth";
import { getMe } from "./generated/me/me";
import type { AuthProviders, Me, MyWorkspace, MyWorkspaceRole } from "./generated/model";
import { clearUserScopedCache } from "./queryClient";

/**
 * Session and sign-in (spec §21.1), on top of the generated client. Wrapped rather than
 * used raw because a 401 from `/me` is an answer ("signed out"), not an error, and
 * because the cache must be cleared whenever the identity changes.
 */
export type { AuthProviders, Me, MyWorkspace };
export type WorkspaceRole = MyWorkspaceRole;

export type LoginSource = { kind: "local" } | { kind: "ldap"; idpId: string };

export const authKeys = {
  me: ["me"] as const,
  providers: ["auth-providers"] as const,
};

/** `null` when signed out. */
export function useMe() {
  return useQuery({
    queryKey: authKeys.me,
    queryFn: async (): Promise<Me | null> => {
      try {
        return await getMe();
      } catch (err) {
        if (err instanceof UnauthorizedError) return null;
        throw err;
      }
    },
    staleTime: 60_000,
  });
}

export function useAuthProviders() {
  return useQuery({ queryKey: authKeys.providers, queryFn: () => getAuthProviders(), staleTime: Infinity });
}

export function useLogin() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (input: { source: LoginSource; username: string; password: string }) => {
      try {
        await login({
          source: input.source.kind,
          idpId: input.source.kind === "ldap" ? input.source.idpId : null,
          username: input.username,
          password: input.password,
        });
      } catch (err) {
        // A refused sign-in is a 401 from this endpoint, not a lost session.
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
    mutationFn: () => logout(),
    onSettled: () => {
      clearUserScopedCache(qc);
      qc.setQueryData(authKeys.me, null);
    },
  });
}

/** Where an SSO button sends the browser; the backend redirects on to the identity provider. */
export function oidcStartUrl(idpId: string): string {
  return `/admin/api/auth/oidc/${encodeURIComponent(idpId)}`;
}
