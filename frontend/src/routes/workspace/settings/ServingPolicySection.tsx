import { useState, type FormEvent } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";

import type { WorkspaceRole } from "@/api/auth";
import { getGetServingPolicyQueryKey, useGetServingPolicy, useSaveServingPolicy } from "@/api/generated/serving-policy/serving-policy";
import type { FirewallMode, ServingPolicy } from "@/api/generated/model";
import { hasRole } from "@/auth/useAuth";
import { QueryBoundary } from "@/components/QueryBoundary";
import { ConfigTable, ConfigTableGroup, ConfigTableRow } from "@/components/config/ConfigTable";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { RadioGroup, RadioGroupItem } from "@/components/ui/radio-group";
import { Textarea } from "@/components/ui/textarea";
import { errorMessage } from "@/lib/errors";
import { useUnsavedChangesWarning } from "@/lib/useUnsavedChangesWarning";

/** Firewall and caller rate limits for every endpoint of the workspace (spec §14). */
export function ServingPolicySection({ ws, role }: { ws: string; role: WorkspaceRole }) {
  const query = useGetServingPolicy(ws);
  return (
    <QueryBoundary query={query} what="the firewall and rate limits">
      {(policy) => <PolicyForm key={JSON.stringify(policy)} ws={ws} policy={policy} canEdit={hasRole(role, "admin")} />}
    </QueryBoundary>
  );
}

const MODES: { value: FirewallMode; label: string; hint: string }[] = [
  { value: "off", label: "Off", hint: "Any address may call" },
  { value: "allow", label: "Allow list", hint: "Only the addresses below may call" },
  { value: "deny", label: "Deny list", hint: "The addresses below may not call" },
];

const toNumber = (s: string) => (s.trim() === "" ? null : Number.parseInt(s, 10));

function PolicyForm({ ws, policy, canEdit }: { ws: string; policy: ServingPolicy; canEdit: boolean }) {
  const qc = useQueryClient();
  const save = useSaveServingPolicy();
  const [mode, setMode] = useState<FirewallMode>(policy.firewall.mode);
  const [entries, setEntries] = useState(policy.firewall.entries.join("\n"));
  const [perClient, setPerClient] = useState(policy.rateLimits.perClientPerMinute?.toString() ?? "");
  const [perIp, setPerIp] = useState(policy.rateLimits.perIpPerMinute?.toString() ?? "");
  const [error, setError] = useState<string | null>(null);
  const next: ServingPolicy = {
    firewall: { mode, entries: entries.split(/[\s,]+/).map((e) => e.trim()).filter(Boolean) },
    rateLimits: { perClientPerMinute: toNumber(perClient), perIpPerMinute: toNumber(perIp) },
  };
  const dirty = JSON.stringify(next) !== JSON.stringify({
    firewall: policy.firewall,
    rateLimits: { perClientPerMinute: policy.rateLimits.perClientPerMinute ?? null, perIpPerMinute: policy.rateLimits.perIpPerMinute ?? null },
  });
  useUnsavedChangesWarning(dirty);

  const submit = (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    save.mutate({ ws, data: next }, {
      onSuccess: (saved) => {
        qc.setQueryData(getGetServingPolicyQueryKey(ws), saved);
        toast.success("Saved. Every instance applies it within a few seconds.");
      },
      onError: (err) => setError(errorMessage(err)),
    });
  };

  return (
    <form onSubmit={submit} className="space-y-4">
      <fieldset disabled={!canEdit} className="min-w-0">
        <ConfigTable valueClassName="w-96" detailsClassName="max-w-prose">
          <ConfigTableGroup label="Firewall" />
          <ConfigTableRow label="Mode" details="Applies to every endpoint of this workspace, public or private">
            <RadioGroup value={mode} onValueChange={(v) => setMode(v as FirewallMode)} aria-label="Firewall mode" className="space-y-1">
              {MODES.map((m) => (
                <div key={m.value} className="flex items-center gap-2">
                  <RadioGroupItem id={`fw-${m.value}`} value={m.value} />
                  <Label htmlFor={`fw-${m.value}`}>{m.label}</Label>
                  <span className="text-xs text-muted-foreground">{m.hint}</span>
                </div>
              ))}
            </RadioGroup>
          </ConfigTableRow>
          <ConfigTableRow label="Addresses" htmlFor="fw-entries" details="One IP address or CIDR range per line, e.g. 10.0.0.0/8 or 2001:db8::/32">
            <Textarea id="fw-entries" rows={5} className="font-mono text-xs" value={entries} disabled={!canEdit || mode === "off"}
              onChange={(e) => setEntries(e.target.value)} />
          </ConfigTableRow>
          <ConfigTableGroup label="Rate limits" />
          <ConfigTableRow label="Per client" htmlFor="rl-client" unit="calls / minute"
            details="For each OAuth2 client calling private endpoints. Empty = no limit.">
            <Input id="rl-client" type="number" min={1} className="w-32 tabular-nums" value={perClient} onChange={(e) => setPerClient(e.target.value)} />
          </ConfigTableRow>
          <ConfigTableRow label="Per IP address" htmlFor="rl-ip" unit="calls / minute"
            details="For each caller address on public endpoints. Empty = no limit.">
            <Input id="rl-ip" type="number" min={1} className="w-32 tabular-nums" value={perIp} onChange={(e) => setPerIp(e.target.value)} />
          </ConfigTableRow>
        </ConfigTable>
      </fieldset>
      <p className="max-w-prose text-xs text-muted-foreground">
        Limits are counted by each server instance separately: with three instances a caller can make up to three times the limit.
        Each endpoint can also set its own limit in its Settings tab.
      </p>
      {error && <p role="alert" className="text-sm text-destructive">{error}</p>}
      {canEdit && <Button type="submit" disabled={!dirty || save.isPending}>Save</Button>}
    </form>
  );
}
