import { useMe, type Me, type MyWorkspace, type WorkspaceRole } from "@/api/auth";

/** The signed-in user. Only call below `RequireAuth`, where `me` is known to be set. */
export function useAuth(): { me: Me } {
  const { data } = useMe();
  if (!data) throw new Error("useAuth used outside RequireAuth");
  return { me: data };
}

const ROLE_RANK: Record<WorkspaceRole, number> = {
  viewer: 0,
  author: 1,
  script_author: 2,
  admin: 3,
  owner: 4,
};

/** Whether `role` is at least `minimum` in the spec §8.4 order. */
export function hasRole(role: WorkspaceRole, minimum: WorkspaceRole): boolean {
  return ROLE_RANK[role] >= ROLE_RANK[minimum];
}

export function findWorkspace(me: Me, slug: string | undefined): MyWorkspace | undefined {
  return slug ? me.workspaces.find((w) => w.slug === slug) : undefined;
}

/**
 * The caller's role in a workspace, as the server decides it: system admins act as
 * owners everywhere (backend `WorkspaceAccess`). `undefined` when not a member.
 */
export function effectiveRole(me: Me, slug: string | undefined): WorkspaceRole | undefined {
  if (me.systemAdmin) return "owner";
  return findWorkspace(me, slug)?.role;
}
