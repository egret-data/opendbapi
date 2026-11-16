import type { WorkspaceRole } from "@/api/auth";

/** Weakest first, as in spec §8.4. */
export const ROLE_ORDER: WorkspaceRole[] = ["viewer", "author", "script_author", "admin", "owner"];

export const ROLE_LABEL: Record<WorkspaceRole, string> = {
  owner: "Owner",
  admin: "Admin",
  script_author: "Script author",
  author: "Author",
  viewer: "Viewer",
};

export const ROLE_HINT: Record<WorkspaceRole, string> = {
  owner: "Everything, including owners and workspace settings",
  admin: "Members, datasources, clients and security",
  script_author: "Author, plus Groovy script endpoints",
  author: "Write, preview and publish SQL endpoints",
  viewer: "Read only",
};
