import { useTranslation } from "react-i18next";

import { useGetEndpoint, useListEndpoints } from "@/api/generated/endpoints/endpoints";
import MonacoEditor from "@/components/monaco/MonacoEditor";
import { Checkbox } from "@/components/ui/checkbox";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { depsOf, formatDep, type Dep, type WfKind, type WfNode } from "./workflowGraph";

const ANY = "any";

/** The kinds a node can be, in the order they are offered. */
export const KINDS: WfKind[] = ["sql", "script", "api", "decision", "end"];

export function useKindLabel() {
  const { t } = useTranslation();
  return (kind: WfKind) => ({
    sql: t("SQL"), script: t("Groovy script"), api: t("Call endpoint"), decision: t("Decision"), end: t("End"),
  })[kind];
}

/**
 * Edits one workflow node (spec §6.1): its id and kind, what it runs, the nodes it comes after
 * (and which branch of a decision), and its condition.
 */
export function NodeForm({ ws, selfId, node, all, onChange, onRename, disabled, canScript, idPrefix }: {
  ws: string;
  selfId: string;
  node: WfNode;
  all: WfNode[];
  onChange: (node: WfNode) => void;
  onRename: (id: string) => void;
  disabled: boolean;
  canScript: boolean;
  idPrefix: string;
}) {
  const { t } = useTranslation();
  const label = useKindLabel();
  const others = all.filter((o) => o.id !== node.id && o.kind !== "end");
  const deps = depsOf(node);
  const setDeps = (next: Dep[]) => onChange({ ...node, dependsOn: next.map(formatDep) });
  const locked = disabled || (node.kind === "script" && !canScript);
  const f = (name: string) => `${idPrefix}-${name}`;

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-end gap-2">
        <div className="space-y-1">
          <Label htmlFor={f("id")}>{t("Id")}</Label>
          <Input id={f("id")} className="w-36 font-mono text-xs" value={node.id} disabled={disabled} onChange={(e) => onRename(e.target.value)} />
        </div>
        <div className="space-y-1">
          <Label htmlFor={f("kind")}>{t("Kind")}</Label>
          <Select value={node.kind} disabled={disabled}
            onValueChange={(v) => onChange({ ...node, kind: v as WfKind,
              template: node.template ?? (v === "sql" ? "select 1" : null), source: node.source ?? (v === "script" ? "return [:]" : null),
              condition: node.condition ?? (v === "decision" ? "true" : null), answer: node.answer ?? (v === "end" ? "null" : null) })}>
            <SelectTrigger id={f("kind")} className="w-40"><SelectValue /></SelectTrigger>
            <SelectContent>
              {KINDS.map((k) => <SelectItem key={k} value={k} disabled={k === "script" && !canScript}>{label(k)}</SelectItem>)}
            </SelectContent>
          </Select>
        </div>
        {node.kind === "sql" && (
          <div className="space-y-1">
            <Label htmlFor={f("result")}>{t("Result")}</Label>
            <Select value={node.result ?? "list"} disabled={disabled} onValueChange={(v) => onChange({ ...node, result: v as WfNode["result"] })}>
              <SelectTrigger id={f("result")} className="w-40"><SelectValue /></SelectTrigger>
              <SelectContent>
                <SelectItem value="list">{t("All rows")}</SelectItem>
                <SelectItem value="one">{t("First row")}</SelectItem>
                <SelectItem value="value">{t("Single value")}</SelectItem>
                <SelectItem value="update">{t("Rows changed")}</SelectItem>
              </SelectContent>
            </Select>
          </div>
        )}
        <div className="min-w-48 flex-1 space-y-1">
          <Label htmlFor={f("when")}>{t("Only when (optional)")}</Label>
          <Input id={f("when")} className="font-mono text-xs" value={node.when ?? ""} disabled={disabled} placeholder="user != null"
            onChange={(e) => onChange({ ...node, when: e.target.value || null })} />
        </div>
      </div>

      {others.length > 0 && (
        <fieldset className="flex flex-wrap items-center gap-3 text-sm" disabled={disabled}>
          <legend className="sr-only">{t("{{id}} runs after", { id: node.id })}</legend>
          <span className="text-muted-foreground">{t("After:")}</span>
          {others.map((o) => {
            const dep = deps.find((d) => d.id === o.id);
            return (
              <span key={o.id} className="flex items-center gap-1.5 font-mono text-xs">
                <label className="flex items-center gap-1.5">
                  <Checkbox checked={!!dep} aria-label={t("{{id}} runs after {{other}}", { id: node.id, other: o.id })}
                    onCheckedChange={(v) => setDeps(v === true ? [...deps, { id: o.id, branch: o.kind === "decision" ? "yes" : null }]
                      : deps.filter((d) => d.id !== o.id))} />
                  {o.id}
                </label>
                {dep && o.kind === "decision" && (
                  <Select value={dep.branch ?? ANY} disabled={disabled}
                    onValueChange={(v) => setDeps(deps.map((d) => (d.id === o.id ? { ...d, branch: v === ANY ? null : v as "yes" | "no" } : d)))}>
                    <SelectTrigger className="h-7 w-24" aria-label={t("Branch of {{id}}", { id: o.id })}><SelectValue /></SelectTrigger>
                    <SelectContent>
                      <SelectItem value="yes">{t("Yes")}</SelectItem>
                      <SelectItem value="no">{t("No")}</SelectItem>
                      <SelectItem value={ANY}>{t("Either")}</SelectItem>
                    </SelectContent>
                  </Select>
                )}
              </span>
            );
          })}
        </fieldset>
      )}

      {(node.kind === "sql" || node.kind === "script") && (
        <div className="h-40 overflow-hidden rounded-md border"
          aria-label={node.kind === "script" ? t("Script of {{id}}", { id: node.id }) : t("SQL of {{id}}", { id: node.id })}>
          <MonacoEditor
            language={node.kind === "script" ? "java" : "sql"}
            value={(node.kind === "script" ? node.source : node.template) ?? ""}
            onChange={(v) => onChange(node.kind === "script" ? { ...node, source: v ?? "" } : { ...node, template: v ?? "" })}
            options={{ minimap: { enabled: false }, fontSize: 13, readOnly: locked, wordWrap: "on", scrollBeyondLastLine: false, lineNumbers: "off" }}
          />
        </div>
      )}
      {node.kind === "api" && <ApiFields ws={ws} selfId={selfId} node={node} onChange={onChange} disabled={disabled} f={f} />}
      {node.kind === "decision" && (
        <div className="space-y-1">
          <Label htmlFor={f("condition")}>{t("Condition (yes when it holds)")}</Label>
          <Input id={f("condition")} className="font-mono text-xs" value={node.condition ?? ""} disabled={disabled} placeholder="count[0].num == 0"
            onChange={(e) => onChange({ ...node, condition: e.target.value })} />
        </div>
      )}
      {node.kind === "end" && (
        <div className="space-y-1">
          <Label htmlFor={f("answer")}>{t("Answer (expression)")}</Label>
          <Input id={f("answer")} className="font-mono text-xs" value={node.answer ?? ""} disabled={disabled} placeholder="add"
            onChange={(e) => onChange({ ...node, answer: e.target.value })} />
        </div>
      )}
    </div>
  );
}

/** An api node: which endpoint, and an expression per parameter it declares. */
function ApiFields({ ws, selfId, node, onChange, disabled, f }: {
  ws: string; selfId: string; node: WfNode; onChange: (node: WfNode) => void; disabled: boolean; f: (name: string) => string;
}) {
  const { t } = useTranslation();
  const endpoints = useListEndpoints(ws);
  const target = useGetEndpoint(ws, node.endpointId ?? "", { query: { enabled: !!node.endpointId } });
  const params = node.params ?? {};
  const declared = target.data?.definition.params.map((p) => p.name) ?? [];
  const names = [...declared, ...Object.keys(params).filter((n) => !declared.includes(n))];

  return (
    <div className="space-y-2">
      <div className="space-y-1">
        <Label htmlFor={f("endpoint")}>{t("Endpoint to call")}</Label>
        <Select value={node.endpointId ?? ""} disabled={disabled} onValueChange={(v) => onChange({ ...node, endpointId: v, params: {} })}>
          <SelectTrigger id={f("endpoint")} className="w-full max-w-md"><SelectValue placeholder={t("Choose")} /></SelectTrigger>
          <SelectContent>
            {(endpoints.data ?? []).filter((e) => e.id !== selfId).map((e) => (
              <SelectItem key={e.id} value={e.id}>{e.method} /{e.path} · {e.name}{e.state !== "live" ? ` (${t("not live")})` : ""}</SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>
      {names.length > 0 && (
        <div className="grid gap-2 sm:grid-cols-2">
          {names.map((name) => (
            <div key={name} className="space-y-1">
              <Label htmlFor={f(`param-${name}`)} className="font-mono text-xs">{name}</Label>
              <Input id={f(`param-${name}`)} className="font-mono text-xs" value={params[name] ?? ""} disabled={disabled} placeholder={name}
                onChange={(e) => onChange({ ...node, params: { ...params, [name]: e.target.value } })} />
            </div>
          ))}
        </div>
      )}
      <p className="text-xs text-muted-foreground">{t("Each value is an expression: a parameter name, a field of an earlier node such as user.id, or a literal like 'x' or 10.")}</p>
    </div>
  );
}
