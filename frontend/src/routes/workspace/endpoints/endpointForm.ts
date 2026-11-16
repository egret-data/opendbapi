import type { EndpointDefinition, EndpointDetail, Param, UpdateEndpoint } from "@/api/generated/model";
import type { StatusTone } from "@/components/ui/status-badge";

export const METHODS = ["GET", "POST", "PUT", "DELETE"] as const;
export type Method = (typeof METHODS)[number];

export const STATE_TONE: Record<string, StatusTone> = { live: "success", draft: "neutral", disabled: "inactive" };
export const STATE_LABEL: Record<string, string> = { live: "Live", draft: "Draft", disabled: "Disabled" };

export const PARAM_TYPES = [
  "string", "bigint", "double", "decimal", "boolean", "date", "datetime",
  "array<string>", "array<bigint>", "array<double>", "array<date>",
] as const;

/** Everything the detail page edits; saved together as one draft (spec §5). */
export type DraftForm = {
  name: string;
  description: string;
  method: Method;
  path: string;
  access: "private" | "public";
  groupId: string | null;
  definition: EndpointDefinition;
};

export function formFrom(detail: EndpointDetail): DraftForm {
  const s = detail.summary;
  return {
    name: s.name,
    description: s.description ?? "",
    method: s.method as Method,
    path: s.path,
    access: s.access as "private" | "public",
    groupId: s.groupId ?? null,
    definition: detail.definition,
  };
}

export function toUpdate(f: DraftForm): UpdateEndpoint {
  return {
    name: f.name.trim(),
    description: f.description.trim() || null,
    method: f.method,
    path: f.path.trim().replace(/^\/+/, ""),
    access: f.access,
    groupId: f.groupId,
    definition: f.definition,
  };
}

/** The SQL of the statement the UI edits (P1: one statement, "main"). */
export function mainSql(def: EndpointDefinition): string {
  const statements = def.task.statements;
  return (statements.find((s) => s.id === "main") ?? statements[statements.length - 1])?.template ?? "";
}

export function withMainSql(def: EndpointDefinition, sql: string): EndpointDefinition {
  const others = def.task.statements.filter((s) => s.id !== "main");
  return { ...def, task: { ...def.task, statements: [...others, { id: "main", template: sql }] } };
}

const RESERVED = new Set(["page", "size", "format"]);

/**
 * Names a template reads: `#{name}`, `#{name.field}`, and variables in `test`/`bind`
 * expressions, minus `<bind>`/`<foreach>` names. A helper for "Detect from SQL";
 * the server's validation is the authority.
 */
export function detectParams(sql: string): string[] {
  const bound = new Set<string>();
  for (const m of sql.matchAll(/<bind\s+name="([A-Za-z_]\w*)"/g)) bound.add(m[1]);
  for (const m of sql.matchAll(/<foreach[^>]*?\b(?:item|index)="([A-Za-z_]\w*)"/g)) bound.add(m[1]);
  const found = new Set<string>();
  for (const m of sql.matchAll(/#\{\s*([A-Za-z_]\w*)/g)) found.add(m[1]);
  for (const m of sql.matchAll(/\b(?:test|value|collection)="([^"]*)"/g)) {
    const expr = m[1].replace(/'[^']*'/g, "").replace(/"[^"]*"/g, "");
    for (const v of expr.matchAll(/(?<![.\w])([A-Za-z_]\w*)/g)) {
      if (!["and", "or", "not", "null", "true", "false", "eq", "neq", "lt", "lte", "gt", "gte", "size", "length", "isEmpty"].includes(v[1])) {
        found.add(v[1]);
      }
    }
  }
  for (const m of sql.matchAll(/<orderBy[^>]*\bparam="([A-Za-z_]\w*)"/g)) found.add(m[1]);
  return [...found].filter((n) => !bound.has(n) && !RESERVED.has(n));
}

export function newParam(name: string, path: string): Param {
  const inPath = new RegExp(`\\{${name}\\}`).test(path);
  return { name, in: inPath ? "path" : "query", type: inPath ? "bigint" : "string", required: inPath };
}
