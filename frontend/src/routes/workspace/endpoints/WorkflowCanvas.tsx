import { useMemo, useState } from "react";
import {
  Background, Controls, Handle, Position, ReactFlow, applyNodeChanges,
  type Edge, type Node, type NodeChange, type NodeProps, type EdgeChange, type Connection,
} from "@xyflow/react";
import "@xyflow/react/dist/style.css";
import { GitBranch, Globe, Code2, Database, Flag, Trash2 } from "lucide-react";
import { useTranslation } from "react-i18next";

import { useListEndpoints } from "@/api/generated/endpoints/endpoints";
import { useTheme } from "@/components/theme-provider";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import { NodeForm, useKindLabel, KINDS } from "./NodeForm";
import {
  NODE_WIDTH, connect, disconnect, edgesOf, newNode, nextId, positions, removeNode, rename, summary,
  type WfKind, type WfNode,
} from "./workflowGraph";

const ICONS: Record<WfKind, typeof Database> = { sql: Database, script: Code2, api: Globe, decision: GitBranch, end: Flag };

interface CardData extends Record<string, unknown> {
  node: WfNode;
  kindLabel: string;
  line: string;
}

/** A node on the canvas: inputs on top; one output below, two (yes/no) for decisions, none for end nodes. */
function Card({ data, selected }: NodeProps<Node<CardData>>) {
  const { t } = useTranslation();
  const { node, kindLabel, line } = data;
  const Icon = ICONS[node.kind];
  return (
    <div className={cn("rounded-md border bg-card px-3 py-2 text-card-foreground shadow-sm", selected && "ring-2 ring-primary")}
      style={{ width: NODE_WIDTH }}>
      <Handle type="target" position={Position.Top} />
      <div className="flex items-center gap-2 text-xs text-muted-foreground">
        <Icon className="h-3.5 w-3.5" aria-hidden="true" />
        {kindLabel}
        {node.when ? <span className="ml-auto font-mono" title={node.when}>{t("if")}</span> : null}
      </div>
      <div className="font-mono text-sm font-semibold">{node.id}</div>
      <div className="truncate font-mono text-xs text-muted-foreground" title={line}>{line || "—"}</div>
      {node.kind === "decision" ? (
        <>
          <Handle type="source" position={Position.Bottom} id="yes" style={{ left: "30%" }} />
          <Handle type="source" position={Position.Bottom} id="no" style={{ left: "70%" }} />
          <div className="mt-1 flex justify-between px-6 text-xs font-semibold text-muted-foreground">
            <span>{t("Yes")}</span><span>{t("No")}</span>
          </div>
        </>
      ) : node.kind !== "end" ? <Handle type="source" position={Position.Bottom} /> : null}
    </div>
  );
}

const NODE_TYPES = { card: Card };

/**
 * Spec §6.1: the workflow as a graph to draw. Drag from a node's bottom handle to another node
 * to make it run after (from Yes or No of a decision for that branch); select an edge and press
 * Delete to remove it; click a node to edit it beside the canvas.
 */
export function WorkflowCanvas({ ws, selfId, nodes, onChange, disabled, canScript }: {
  ws: string; selfId: string; nodes: WfNode[]; onChange: (nodes: WfNode[]) => void; disabled: boolean; canScript: boolean;
}) {
  const { t } = useTranslation();
  const label = useKindLabel();
  const { theme = "system" } = useTheme();
  const endpoints = useListEndpoints(ws);
  const names = useMemo(() => new Map((endpoints.data ?? []).map((e) => [e.id, `${e.method} /${e.path}`])), [endpoints.data]);
  const [selected, setSelected] = useState<string | null>(null);

  const build = (list: WfNode[]): Node<CardData>[] => {
    const at = positions(list);
    return list.map((n) => ({
      id: n.id, type: "card", position: at.get(n.id) ?? { x: 0, y: 0 }, deletable: false, selected: n.id === selected,
      data: { node: n, kindLabel: label(n.kind), line: summary(n, n.endpointId ? names.get(n.endpointId) : undefined) },
    }));
  };
  const [flow, setFlow] = useState<Node<CardData>[]>(() => build(nodes));
  // Rebuild the canvas when the nodes, the endpoint names or the selection change (React's
  // "adjust state while rendering" pattern); dragging only moves the local copy until it stops.
  const [seen, setSeen] = useState({ nodes, names, selected });
  if (seen.nodes !== nodes || seen.names !== names || seen.selected !== selected) {
    setSeen({ nodes, names, selected });
    setFlow(build(nodes));
  }

  const edges: Edge[] = edgesOf(nodes).map((e) => ({
    id: e.id, source: e.source, target: e.target, sourceHandle: e.sourceHandle ?? undefined,
    label: e.sourceHandle === "yes" ? t("Yes") : e.sourceHandle === "no" ? t("No") : undefined,
    animated: false, deletable: !disabled,
  }));

  const onNodesChange = (changes: NodeChange<Node<CardData>>[]) => setFlow((f) => applyNodeChanges(changes, f));
  const onEdgesChange = (changes: EdgeChange[]) => {
    let next = nodes;
    for (const c of changes) if (c.type === "remove") next = disconnect(next, c.id);
    if (next !== nodes) onChange(next);
  };
  const onConnect = (c: Connection) => {
    if (!c.source || !c.target) return;
    onChange(connect(nodes, c.source, c.target, c.sourceHandle === "yes" || c.sourceHandle === "no" ? c.sourceHandle : null));
  };
  const add = (kind: WfKind) => {
    const at = positions(nodes);
    const lowest = Math.max(0, ...[...at.values()].map((p) => p.y + 150));
    const id = nextId(nodes, kind === "decision" ? "check" : kind === "end" ? "end" : "step");
    onChange([...nodes, newNode(kind, id, { x: 0, y: lowest })]);
    setSelected(id);
  };
  const current = nodes.find((n) => n.id === selected) ?? null;

  return (
    <div className="grid gap-3 lg:grid-cols-[minmax(0,1fr)_minmax(0,26rem)]">
      <div className="space-y-2">
        {!disabled && (
          <div className="flex flex-wrap gap-1" role="toolbar" aria-label={t("Add a node")}>
            {KINDS.map((k) => {
              const Icon = ICONS[k];
              return (
                <Button key={k} type="button" variant="outline" size="sm" disabled={(k === "script" && !canScript) || nodes.length >= 20}
                  onClick={() => add(k)}>
                  <Icon aria-hidden="true" />
                  {label(k)}
                </Button>
              );
            })}
          </div>
        )}
        <div className="h-[30rem] overflow-hidden rounded-md border" aria-label={t("Workflow canvas")}>
          <ReactFlow
            nodes={flow}
            edges={edges}
            nodeTypes={NODE_TYPES}
            colorMode={theme === "dark" ? "dark" : theme === "light" ? "light" : "system"}
            onNodesChange={onNodesChange}
            onEdgesChange={onEdgesChange}
            onConnect={disabled ? undefined : onConnect}
            onNodeClick={(_, n) => setSelected(n.id)}
            onPaneClick={() => setSelected(null)}
            onNodeDragStop={(_, n) => {
              if (disabled) return;
              onChange(nodes.map((x) => (x.id === n.id ? { ...x, x: Math.round(n.position.x), y: Math.round(n.position.y) } : x)));
            }}
            nodesDraggable={!disabled}
            nodesConnectable={!disabled}
            deleteKeyCode={disabled ? null : ["Delete", "Backspace"]}
            fitView
            fitViewOptions={{ padding: 0.2, maxZoom: 1 }}
            proOptions={{ hideAttribution: true }}
          >
            <Background gap={16} />
            <Controls showInteractive={false} />
          </ReactFlow>
        </div>
        <p className="text-xs text-muted-foreground">
          {t("Drag from the bottom of a node to another to make it run after; from Yes or No of a decision for that branch. Select a line and press Delete to remove it.")}
        </p>
      </div>
      <aside aria-label={t("Selected node")} className="rounded-md border p-3">
        {current ? (
          <div className="space-y-3">
            <NodeForm ws={ws} selfId={selfId} node={current} all={nodes} disabled={disabled} canScript={canScript} idPrefix="wfc"
              onChange={(next) => onChange(nodes.map((n) => (n.id === current.id ? next : n)))}
              onRename={(id) => { onChange(rename(nodes, current.id, id)); setSelected(id); }} />
            {!disabled && (
              <Button type="button" variant="outline" size="sm" disabled={nodes.length === 1}
                onClick={() => { onChange(removeNode(nodes, current.id)); setSelected(null); }}>
                <Trash2 aria-hidden="true" />
                {t("Remove {{id}}", { id: current.id })}
              </Button>
            )}
          </div>
        ) : (
          <p className="text-sm text-muted-foreground">{t("Click a node to edit it.")}</p>
        )}
      </aside>
    </div>
  );
}
