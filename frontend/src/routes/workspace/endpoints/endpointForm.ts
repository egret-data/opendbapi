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

export const SCRIPT_STARTER = `// Values in \${…} are bound as parameters, never pasted into the SQL.
def q = sql"select 1 as ok"
return q
`;

export const FULL_SCRIPT_STARTER = `// db.one / db.list / db.value / db.update run queries; return any value.
def row = db.one(sql"select 1 as id, 'example' as name where 1 = \${params.id ?: 1}")
if (!row) return notFound("Nothing found")
return [item: row]
`;

export function isScript(def: EndpointDefinition): boolean {
  return def.task.type === "script";
}

export type TaskKind = "sql" | "fragment" | "full" | "workflow";

export function taskKind(def: EndpointDefinition): TaskKind {
  if (def.task.type === "workflow") return "workflow";
  return !isScript(def) ? "sql" : def.task.mode === "full" ? "full" : "fragment";
}

/** Whether saving or previewing needs the script author role: a script, or a workflow with a script node. */
export function needsScriptRole(def: EndpointDefinition): boolean {
  return isScript(def) || (def.task.type === "workflow" && (def.task.nodes ?? []).some((n) => n?.kind === "script"));
}

/** Names of workflow nodes: results available to later nodes, not request parameters. */
export function nodeIds(def: EndpointDefinition): string[] {
  return def.task.type === "workflow" ? (def.task.nodes ?? []).flatMap((n) => (n ? [n.id] : [])) : [];
}

/** Switches between an SQL template and a Groovy script (fragment or full mode); texts are kept. */
export function withLanguage(def: EndpointDefinition, kind: TaskKind): EndpointDefinition {
  const task = def.task;
  if (kind === "sql") return { ...def, task: { ...task, type: "sql" } };
  if (kind === "workflow") {
    const statements = task.statements;
    const sql = (statements.find((s) => s.id === "main") ?? statements[statements.length - 1])?.template ?? "select 1";
    const nodes = task.nodes && task.nodes.length > 0 ? task.nodes
      : [{ id: "main", kind: "sql" as const, template: sql, source: null, dependsOn: [], when: null, result: "list" as const }];
    return { ...def, task: { ...task, type: "workflow", nodes } };
  }
  const starter = kind === "full" ? FULL_SCRIPT_STARTER : SCRIPT_STARTER;
  const source = !task.source || task.source === SCRIPT_STARTER || task.source === FULL_SCRIPT_STARTER ? starter : task.source;
  return { ...def, task: { ...task, type: "script", language: "groovy", mode: kind, source } };
}

/** The text the UI edits: the main SQL statement, or the script. */
export function mainSql(def: EndpointDefinition): string {
  if (isScript(def)) return def.task.source ?? "";
  if (def.task.type === "workflow") {
    return (def.task.nodes ?? []).flatMap((n) => (n ? [`-- node ${n.id}\n${(n.kind === "script" ? n.source : n.template) ?? ""}`] : []))
      .join("\n\n");
  }
  const statements = def.task.statements;
  return (statements.find((s) => s.id === "main") ?? statements[statements.length - 1])?.template ?? "";
}

export function withMainSql(def: EndpointDefinition, sql: string): EndpointDefinition {
  if (isScript(def)) return { ...def, task: { ...def.task, source: sql } };
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
  // Groovy scripts read params.name or params['name'].
  for (const m of sql.matchAll(/\bparams\.([A-Za-z_]\w*)/g)) found.add(m[1]);
  for (const m of sql.matchAll(/\bparams\[\s*['"]([A-Za-z_]\w*)['"]\s*\]/g)) found.add(m[1]);
  return [...found].filter((n) => !bound.has(n) && !RESERVED.has(n));
}

export function newParam(name: string, path: string): Param {
  const inPath = new RegExp(`\\{${name}\\}`).test(path);
  return { name, in: inPath ? "path" : "query", type: inPath ? "bigint" : "string", required: inPath };
}
