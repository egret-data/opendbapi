import { useState } from "react";
import { ArrowDown, ArrowUp, Plus, Trash2 } from "lucide-react";
import { useTranslation } from "react-i18next";

import type { EndpointDefinition } from "@/api/generated/model";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { NodeForm } from "./NodeForm";
import { WorkflowCanvas } from "./WorkflowCanvas";
import { newNode, nextId, removeNode, rename, workflowSteps, type WfNode } from "./workflowGraph";

export { workflowSteps } from "./workflowGraph";

type Task = EndpointDefinition["task"];

const LAST = "__last__";
const VIEW_KEY = "opendbapi.workflowView";

function savedView(): "canvas" | "list" {
  try {
    return localStorage.getItem(VIEW_KEY) === "list" ? "list" : "canvas";
  } catch {
    return "canvas";
  }
}

/**
 * Spec §6.1: edits a workflow task, drawn on a canvas or as a list of nodes (the list suits
 * keyboards and screen readers). Both edit the same nodes.
 */
export function WorkflowEditor({ ws, selfId, task, onChange, disabled, canScript }: {
  ws: string; selfId: string; task: Task; onChange: (task: Task) => void; disabled: boolean; canScript: boolean;
}) {
  const { t } = useTranslation();
  const [view, setView] = useState<"canvas" | "list">(savedView);
  const nodes = (task.nodes ?? []).filter((n): n is WfNode => n != null);
  const steps = workflowSteps(nodes);
  const setNodes = (next: WfNode[]) => onChange({ ...task, nodes: next, output: task.output && next.some((n) => n.id === task.output) ? task.output : null });
  const chooseView = (v: string) => {
    const next = v === "list" ? "list" : "canvas";
    setView(next);
    try {
      localStorage.setItem(VIEW_KEY, next);
    } catch {
      // the choice is a convenience
    }
  };

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <p className="text-sm" aria-live="polite">
          {steps === null
            ? <span className="text-destructive">{t("The nodes depend on each other in a cycle.")}</span>
            : <>{t("Runs in {{n}} step(s):", { n: steps.length })} <span className="font-mono text-xs">{steps.map((s) => s.join(" + ")).join(" → ")}</span></>}
        </p>
        <div className="flex flex-wrap items-center gap-2">
          <Label htmlFor="wf-output">{t("Answer")}</Label>
          <Select value={task.output || LAST} disabled={disabled} onValueChange={(v) => onChange({ ...task, output: v === LAST ? null : v })}>
            <SelectTrigger id="wf-output" className="w-56"><SelectValue /></SelectTrigger>
            <SelectContent>
              <SelectItem value={LAST}>{t("Last node that ran")}</SelectItem>
              {nodes.map((n) => <SelectItem key={n.id} value={n.id}>{n.id}</SelectItem>)}
            </SelectContent>
          </Select>
          <Tabs value={view} onValueChange={chooseView}>
            <TabsList>
              <TabsTrigger value="canvas">{t("Canvas")}</TabsTrigger>
              <TabsTrigger value="list">{t("List")}</TabsTrigger>
            </TabsList>
          </Tabs>
        </div>
      </div>
      {view === "canvas" ? (
        <WorkflowCanvas ws={ws} selfId={selfId} nodes={nodes} onChange={setNodes} disabled={disabled} canScript={canScript} />
      ) : (
        <NodeList ws={ws} selfId={selfId} nodes={nodes} onChange={setNodes} disabled={disabled} canScript={canScript} />
      )}
      <p className="text-xs text-muted-foreground">
        {t("A node reads earlier results by id: #{user.id} in SQL, params.user in a script, user.id in expressions. Nodes in the same step run in parallel unless the task is a transaction.")}
      </p>
    </div>
  );
}

function NodeList({ ws, selfId, nodes, onChange, disabled, canScript }: {
  ws: string; selfId: string; nodes: WfNode[]; onChange: (nodes: WfNode[]) => void; disabled: boolean; canScript: boolean;
}) {
  const { t } = useTranslation();
  const move = (i: number, by: number) => {
    const j = i + by;
    if (j < 0 || j >= nodes.length) return;
    const next = [...nodes];
    [next[i], next[j]] = [next[j], next[i]];
    onChange(next);
  };
  return (
    <>
      <ol className="space-y-3">
        {nodes.map((n, i) => (
          <li key={i} aria-label={t("Node {{id}}", { id: n.id })} className="space-y-2 rounded-md border p-3">
            <NodeForm ws={ws} selfId={selfId} node={n} all={nodes} disabled={disabled} canScript={canScript} idPrefix={`wf-${i}`}
              onChange={(next) => onChange(nodes.map((x, j) => (j === i ? next : x)))}
              onRename={(id) => onChange(rename(nodes, n.id, id))} />
            {!disabled && (
              <div className="flex gap-1">
                <Button type="button" variant="ghost" size="icon" aria-label={t("Move {{id}} up", { id: n.id })} disabled={i === 0}
                  onClick={() => move(i, -1)}><ArrowUp aria-hidden="true" /></Button>
                <Button type="button" variant="ghost" size="icon" aria-label={t("Move {{id}} down", { id: n.id })}
                  disabled={i === nodes.length - 1} onClick={() => move(i, 1)}><ArrowDown aria-hidden="true" /></Button>
                <Button type="button" variant="ghost" size="icon" aria-label={t("Remove {{id}}", { id: n.id })}
                  disabled={nodes.length === 1} onClick={() => onChange(removeNode(nodes, n.id))}><Trash2 aria-hidden="true" /></Button>
              </div>
            )}
          </li>
        ))}
      </ol>
      {!disabled && (
        <Button type="button" variant="outline" size="sm" disabled={nodes.length >= 20}
          onClick={() => {
            const id = nextId(nodes);
            const last = nodes[nodes.length - 1];
            onChange([...nodes, { ...newNode("sql", id), dependsOn: last && last.kind !== "end" ? [last.id] : [] }]);
          }}>
          <Plus aria-hidden="true" />
          {t("Add node")}
        </Button>
      )}
    </>
  );
}
