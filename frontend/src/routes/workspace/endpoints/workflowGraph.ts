import type { WorkflowNode } from "@/api/generated/model";

export type WfNode = NonNullable<WorkflowNode>;
export type WfKind = WfNode["kind"];

/** A dependency as written in dependsOn: "id", "id:yes" or "id:no". */
export interface Dep {
  id: string;
  branch: "yes" | "no" | null;
}

export function parseDep(raw: string): Dep {
  const [id, branch] = raw.split(":");
  return { id, branch: branch === "yes" || branch === "no" ? branch : null };
}

export function formatDep(dep: Dep): string {
  return dep.branch ? `${dep.id}:${dep.branch}` : dep.id;
}

export function depsOf(node: WfNode): Dep[] {
  return (node.dependsOn ?? []).filter((d): d is string => !!d).map(parseDep);
}

/** Orders node ids into steps; nodes of one step run at the same time. Null when there is a cycle. */
export function workflowSteps(nodes: WfNode[]): string[][] | null {
  const ids = new Set(nodes.map((n) => n.id));
  const done = new Set<string>();
  const steps: string[][] = [];
  while (done.size < nodes.length) {
    const step = nodes.filter((n) => !done.has(n.id) && depsOf(n).every((d) => done.has(d.id) || !ids.has(d.id))).map((n) => n.id);
    if (step.length === 0) return null;
    step.forEach((id) => done.add(id));
    steps.push(step);
  }
  return steps;
}

export const NODE_WIDTH = 220;
const COLUMN = 280;
const ROW = 150;

/** Where each node sits on the canvas: its saved position, else a grid by step. */
export function positions(nodes: WfNode[]): Map<string, { x: number; y: number }> {
  const steps = workflowSteps(nodes) ?? [nodes.map((n) => n.id)];
  const out = new Map<string, { x: number; y: number }>();
  steps.forEach((step, row) => step.forEach((id, col) => {
    const n = nodes.find((x) => x.id === id);
    out.set(id, n?.x != null && n?.y != null ? { x: n.x, y: n.y } : { x: col * COLUMN, y: row * ROW });
  }));
  return out;
}

/** An edge on the canvas: source runs before target; sourceHandle is the decision branch. */
export interface WfEdge {
  id: string;
  source: string;
  target: string;
  sourceHandle: "yes" | "no" | null;
}

export function edgesOf(nodes: WfNode[]): WfEdge[] {
  return nodes.flatMap((n) => depsOf(n).map((d) => ({
    id: `${d.id}${d.branch ? `:${d.branch}` : ""}->${n.id}`,
    source: d.id,
    target: n.id,
    sourceHandle: d.branch,
  })));
}

/** Connects source to target (a branch of a decision when given); a connection that would loop is ignored. */
export function connect(nodes: WfNode[], source: string, target: string, branch: "yes" | "no" | null): WfNode[] {
  if (source === target) return nodes;
  const sourceNode = nodes.find((n) => n.id === source);
  if (!sourceNode || sourceNode.kind === "end") return nodes;
  const next = nodes.map((n) => (n.id === target
    ? { ...n, dependsOn: [...depsOf(n).filter((d) => d.id !== source).map(formatDep), formatDep({ id: source, branch: sourceNode.kind === "decision" ? branch : null })] }
    : n));
  return workflowSteps(next) === null ? nodes : next;
}

export function disconnect(nodes: WfNode[], edgeId: string): WfNode[] {
  const edge = edgesOf(nodes).find((e) => e.id === edgeId);
  if (!edge) return nodes;
  return nodes.map((n) => (n.id === edge.target
    ? { ...n, dependsOn: depsOf(n).filter((d) => d.id !== edge.source).map(formatDep) }
    : n));
}

/** Renames a node and every reference to it. */
export function rename(nodes: WfNode[], from: string, to: string): WfNode[] {
  return nodes.map((n) => ({
    ...(n.id === from ? { ...n, id: to } : n),
    dependsOn: depsOf(n).map((d) => formatDep(d.id === from ? { ...d, id: to } : d)),
  }));
}

export function removeNode(nodes: WfNode[], id: string): WfNode[] {
  return nodes.filter((n) => n.id !== id).map((n) => ({ ...n, dependsOn: depsOf(n).filter((d) => d.id !== id).map(formatDep) }));
}

export function nextId(nodes: WfNode[], prefix = "step"): string {
  for (let i = nodes.length + 1; ; i++) {
    const id = `${prefix}${i}`;
    if (!nodes.some((n) => n.id === id)) return id;
  }
}

/** A fresh node of a kind, with the fields that kind needs. */
export function newNode(kind: WfKind, id: string, at?: { x: number; y: number }): WfNode {
  const base: WfNode = { id, kind, dependsOn: [], when: null, x: at?.x ?? null, y: at?.y ?? null };
  switch (kind) {
    case "script": return { ...base, source: "return [:]" };
    case "api": return { ...base, endpointId: null, params: {} };
    case "decision": return { ...base, condition: "true" };
    case "end": return { ...base, answer: "null" };
    default: return { ...base, template: "select 1", result: "list" };
  }
}

/** One line describing the node, for the canvas card. */
export function summary(node: WfNode, endpointName?: string): string {
  switch (node.kind) {
    case "sql": return (node.template ?? "").split("\n")[0].slice(0, 60);
    case "script": return (node.source ?? "").split("\n")[0].slice(0, 60);
    case "api": return endpointName ?? "—";
    case "decision": return node.condition ?? "";
    case "end": return node.answer ?? "";
    default: return "";
  }
}
