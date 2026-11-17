import { ArrowDown, ArrowUp, Plus, Trash2 } from "lucide-react";
import { useTranslation } from "react-i18next";

import type { EndpointDefinition, WorkflowNode } from "@/api/generated/model";
import MonacoEditor from "@/components/monaco/MonacoEditor";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";

type Task = EndpointDefinition["task"];
type Node = NonNullable<WorkflowNode>;

const LAST = "__last__";

/** Orders node ids into steps; nodes of one step run at the same time. Null when there is a cycle. */
export function workflowSteps(nodes: Node[]): string[][] | null {
  const ids = new Set(nodes.map((n) => n.id));
  const done = new Set<string>();
  const steps: string[][] = [];
  while (done.size < nodes.length) {
    const step = nodes.filter((n) => !done.has(n.id) && (n.dependsOn ?? []).every((d) => !d || done.has(d) || !ids.has(d)))
      .map((n) => n.id);
    if (step.length === 0) return null;
    step.forEach((id) => done.add(id));
    steps.push(step);
  }
  return steps;
}

function nextId(nodes: Node[]): string {
  for (let i = nodes.length + 1; ; i++) {
    const id = `step${i}`;
    if (!nodes.some((n) => n.id === id)) return id;
  }
}

/** Spec §6.1: edits the nodes of a workflow task, each with its SQL or script, dependencies and condition. */
export function WorkflowEditor({ task, onChange, disabled, canScript }: {
  task: Task; onChange: (task: Task) => void; disabled: boolean; canScript: boolean;
}) {
  const { t } = useTranslation();
  const nodes = (task.nodes ?? []).filter((n): n is Node => n != null);
  const steps = workflowSteps(nodes);
  const setNodes = (next: Node[]) => onChange({ ...task, nodes: next });
  const update = (i: number, patch: Partial<Node>) => setNodes(nodes.map((n, j) => (j === i ? { ...n, ...patch } : n)));
  const rename = (i: number, id: string) => {
    const old = nodes[i].id;
    setNodes(nodes.map((n, j) => (j === i ? { ...n, id }
      : { ...n, dependsOn: (n.dependsOn ?? []).map((d) => (d === old ? id : d)) })));
    if (task.output === old) onChange({ ...task, output: id, nodes: nodes.map((n, j) => (j === i ? { ...n, id } : n)) });
  };
  const move = (i: number, by: number) => {
    const j = i + by;
    if (j < 0 || j >= nodes.length) return;
    const next = [...nodes];
    [next[i], next[j]] = [next[j], next[i]];
    setNodes(next);
  };
  const remove = (i: number) => {
    const gone = nodes[i].id;
    setNodes(nodes.filter((_, j) => j !== i).map((n) => ({ ...n, dependsOn: (n.dependsOn ?? []).filter((d) => d !== gone) })));
  };

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <p className="text-sm" aria-live="polite">
          {steps === null
            ? <span className="text-destructive">{t("The nodes depend on each other in a cycle.")}</span>
            : <>{t("Runs in {{n}} step(s):", { n: steps.length })} <span className="font-mono text-xs">{steps.map((s) => s.join(" + ")).join(" → ")}</span></>}
        </p>
        <div className="flex items-center gap-2">
          <Label htmlFor="wf-output">{t("Answer")}</Label>
          <Select value={task.output || LAST} disabled={disabled} onValueChange={(v) => onChange({ ...task, output: v === LAST ? null : v })}>
            <SelectTrigger id="wf-output" className="w-48"><SelectValue /></SelectTrigger>
            <SelectContent>
              <SelectItem value={LAST}>{t("Last node")}</SelectItem>
              {nodes.map((n) => <SelectItem key={n.id} value={n.id}>{n.id}</SelectItem>)}
            </SelectContent>
          </Select>
        </div>
      </div>
      <ol className="space-y-3">
        {nodes.map((n, i) => {
          const others = nodes.filter((o) => o.id !== n.id);
          const locked = disabled || (n.kind === "script" && !canScript);
          return (
            <li key={i} aria-label={t("Node {{id}}", { id: n.id })} className="space-y-2 rounded-md border p-3">
              <div className="flex flex-wrap items-end gap-2">
                <div className="space-y-1">
                  <Label htmlFor={`wf-id-${i}`}>{t("Id")}</Label>
                  <Input id={`wf-id-${i}`} className="w-36 font-mono text-xs" value={n.id} disabled={disabled}
                    onChange={(e) => rename(i, e.target.value)} />
                </div>
                <div className="space-y-1">
                  <Label htmlFor={`wf-kind-${i}`}>{t("Kind")}</Label>
                  <Select value={n.kind} disabled={disabled || !canScript}
                    onValueChange={(v) => update(i, v === "script"
                      ? { kind: "script", source: n.source || "return [:]" }
                      : { kind: "sql", template: n.template || "select 1" })}>
                    <SelectTrigger id={`wf-kind-${i}`} className="w-36"><SelectValue /></SelectTrigger>
                    <SelectContent>
                      <SelectItem value="sql">{t("SQL")}</SelectItem>
                      <SelectItem value="script">{t("Groovy script")}</SelectItem>
                    </SelectContent>
                  </Select>
                </div>
                {n.kind === "sql" && (
                  <div className="space-y-1">
                    <Label htmlFor={`wf-result-${i}`}>{t("Result")}</Label>
                    <Select value={n.result ?? "list"} disabled={disabled} onValueChange={(v) => update(i, { result: v as Node["result"] })}>
                      <SelectTrigger id={`wf-result-${i}`} className="w-40"><SelectValue /></SelectTrigger>
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
                  <Label htmlFor={`wf-when-${i}`}>{t("Only when (optional)")}</Label>
                  <Input id={`wf-when-${i}`} className="font-mono text-xs" value={n.when ?? ""} disabled={disabled} placeholder="user != null"
                    onChange={(e) => update(i, { when: e.target.value || null })} />
                </div>
                {!disabled && (
                  <div className="flex gap-1">
                    <Button type="button" variant="ghost" size="icon" aria-label={t("Move {{id}} up", { id: n.id })} disabled={i === 0}
                      onClick={() => move(i, -1)}><ArrowUp aria-hidden="true" /></Button>
                    <Button type="button" variant="ghost" size="icon" aria-label={t("Move {{id}} down", { id: n.id })}
                      disabled={i === nodes.length - 1} onClick={() => move(i, 1)}><ArrowDown aria-hidden="true" /></Button>
                    <Button type="button" variant="ghost" size="icon" aria-label={t("Remove {{id}}", { id: n.id })}
                      disabled={nodes.length === 1} onClick={() => remove(i)}><Trash2 aria-hidden="true" /></Button>
                  </div>
                )}
              </div>
              {others.length > 0 && (
                <fieldset className="flex flex-wrap items-center gap-3 text-sm" disabled={disabled}>
                  <legend className="sr-only">{t("{{id}} runs after", { id: n.id })}</legend>
                  <span className="text-muted-foreground">{t("After:")}</span>
                  {others.map((o) => {
                    const checked = (n.dependsOn ?? []).includes(o.id);
                    return (
                      <label key={o.id} className="flex items-center gap-1.5 font-mono text-xs">
                        <Checkbox checked={checked} aria-label={t("{{id}} runs after {{other}}", { id: n.id, other: o.id })}
                          onCheckedChange={(v) => update(i, { dependsOn: v === true
                            ? [...(n.dependsOn ?? []).filter((d): d is string => !!d), o.id]
                            : (n.dependsOn ?? []).filter((d) => d !== o.id) })} />
                        {o.id}
                      </label>
                    );
                  })}
                </fieldset>
              )}
              <div className="h-40 overflow-hidden rounded-md border" aria-label={n.kind === "script" ? t("Script of {{id}}", { id: n.id }) : t("SQL of {{id}}", { id: n.id })}>
                <MonacoEditor
                  language={n.kind === "script" ? "java" : "sql"}
                  value={(n.kind === "script" ? n.source : n.template) ?? ""}
                  onChange={(v) => update(i, n.kind === "script" ? { source: v ?? "" } : { template: v ?? "" })}
                  options={{ minimap: { enabled: false }, fontSize: 13, readOnly: locked, wordWrap: "on", scrollBeyondLastLine: false, lineNumbers: "off" }}
                />
              </div>
            </li>
          );
        })}
      </ol>
      {!disabled && (
        <Button type="button" variant="outline" size="sm" disabled={nodes.length >= 20}
          onClick={() => setNodes([...nodes, { id: nextId(nodes), kind: "sql", template: "select 1", source: null,
            dependsOn: nodes.length ? [nodes[nodes.length - 1].id] : [], when: null, result: "list" }])}>
          <Plus aria-hidden="true" />
          {t("Add node")}
        </Button>
      )}
      <p className="text-xs text-muted-foreground">
        {t("A node reads earlier results by id: #{user.id} in SQL, params.user in a script. Nodes in the same step run in parallel unless the task is a transaction.")}
      </p>
    </div>
  );
}
