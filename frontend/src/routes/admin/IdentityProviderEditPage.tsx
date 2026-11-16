import { useId, useState, type FormEvent } from "react";
import { Link, useNavigate, useParams, useSearchParams } from "react-router-dom";
import { Plus, Trash2 } from "lucide-react";
import { useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";

import {
  getGetGroupMappingsQueryKey,
  getGetIdentityProviderQueryKey,
  getListIdentityProvidersQueryKey,
  useCreateIdentityProvider,
  useDeleteIdentityProvider,
  useGetGroupMappings,
  useGetIdentityProvider,
  useReplaceGroupMappings,
  useTestIdentityProvider,
  useUpdateIdentityProvider,
} from "@/api/generated/identity-providers/identity-providers";
import { useListWorkspaces } from "@/api/generated/workspaces/workspaces";
import type { MappingView, MappingViewRole, ProviderView, TestResult } from "@/api/generated/model";
import { QueryBoundary } from "@/components/QueryBoundary";
import { ConfigTable, ConfigTableGroup, ConfigTableRow } from "@/components/config/ConfigTable";
import { DetailHeader } from "@/components/layout/DetailHeader";
import { PageContainer } from "@/components/layout/PageContainer";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { ConfirmDeleteDialog } from "@/components/ui/confirm-delete-dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { RadioGroup, RadioGroupItem } from "@/components/ui/radio-group";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { StatusBadge } from "@/components/ui/status-badge";
import { Switch } from "@/components/ui/switch";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { errorMessage } from "@/lib/errors";
import { ROLE_LABEL } from "@/lib/roles";
import { useUnsavedChangesWarning } from "@/lib/useUnsavedChangesWarning";
import {
  FIELDS,
  SECRET_LABEL,
  emptyForm,
  formFrom,
  toRequest,
  type FormState,
  type ProviderType,
} from "./providerForm";

/** `/admin/identity-providers/new` and `/admin/identity-providers/:id`. */
export default function IdentityProviderEditPage() {
  const { id } = useParams();
  return id && id !== "new" ? <ExistingProvider id={id} /> : <NewProvider />;
}

function NewProvider() {
  const navigate = useNavigate();
  const qc = useQueryClient();
  const create = useCreateIdentityProvider();
  const [form, setForm] = useState<FormState>(emptyForm("ldap"));
  const [error, setError] = useState<string | null>(null);
  const dirty = form.name !== "" || Object.keys(form.values).length > 0 || form.secret !== "";
  useUnsavedChangesWarning(dirty && !create.isSuccess);

  const submit = (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    create.mutate(
      { data: toRequest(form) },
      {
        onSuccess: (created) => {
          qc.invalidateQueries({ queryKey: getListIdentityProvidersQueryKey() });
          navigate(`/admin/identity-providers/${created.id}?tab=mappings`, { replace: true });
        },
        onError: (err) => setError(errorMessage(err)),
      },
    );
  };

  return (
    <PageContainer>
      <DetailHeader back={{ to: "/admin/identity-providers", label: "Identity providers" }} title="New identity provider" />
      <form onSubmit={submit} className="space-y-4">
        <RadioGroup
          aria-label="Provider type"
          value={form.type}
          onValueChange={(t) => setForm({ ...emptyForm(t as ProviderType), name: form.name })}
          className="flex gap-6"
        >
          <div className="flex items-center gap-2">
            <RadioGroupItem id="type-ldap" value="ldap" />
            <Label htmlFor="type-ldap">LDAP / Active Directory</Label>
          </div>
          <div className="flex items-center gap-2">
            <RadioGroupItem id="type-oidc" value="oidc" />
            <Label htmlFor="type-oidc">SSO (OpenID Connect)</Label>
          </div>
        </RadioGroup>
        <ProviderFields form={form} onChange={setForm} hasSecret={false} />
        {error && (
          <p role="alert" className="text-sm text-destructive">
            {error}
          </p>
        )}
        <div className="flex gap-2">
          <Button type="submit" disabled={create.isPending}>
            Create provider
          </Button>
          <Button asChild variant="outline">
            <Link to="/admin/identity-providers">Cancel</Link>
          </Button>
        </div>
      </form>
    </PageContainer>
  );
}

function ExistingProvider({ id }: { id: string }) {
  const query = useGetIdentityProvider(id);
  const [params, setParams] = useSearchParams();
  const tab = params.get("tab") === "mappings" ? "mappings" : "settings";

  return (
    <PageContainer>
      <QueryBoundary query={query} what="the identity provider">
        {(provider) => (
          <>
            <DetailHeader
              back={{ to: "/admin/identity-providers", label: "Identity providers" }}
              title={provider.name}
              status={
                <StatusBadge tone={provider.enabled ? "success" : "inactive"}>
                  {provider.enabled ? "Enabled" : "Disabled"}
                </StatusBadge>
              }
              description={provider.type === "ldap" ? "LDAP / Active Directory" : "SSO (OpenID Connect)"}
            />
            <Tabs value={tab} onValueChange={(t) => setParams({ tab: t }, { replace: true })}>
              <TabsList>
                <TabsTrigger value="settings">Settings</TabsTrigger>
                <TabsTrigger value="mappings">Group mappings</TabsTrigger>
              </TabsList>
              <TabsContent value="settings" className="pt-4">
                <SettingsTab key={provider.updatedAt} provider={provider} />
              </TabsContent>
              <TabsContent value="mappings" className="pt-4">
                <MappingsTab provider={provider} />
              </TabsContent>
            </Tabs>
          </>
        )}
      </QueryBoundary>
    </PageContainer>
  );
}

function SettingsTab({ provider }: { provider: ProviderView }) {
  const qc = useQueryClient();
  const navigate = useNavigate();
  const update = useUpdateIdentityProvider();
  const remove = useDeleteIdentityProvider();
  const initial = formFrom(provider);
  const [form, setForm] = useState<FormState>(initial);
  const [error, setError] = useState<string | null>(null);
  const [deleting, setDeleting] = useState(false);
  const dirty = JSON.stringify(form) !== JSON.stringify(initial);
  useUnsavedChangesWarning(dirty);

  const save = (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    update.mutate(
      { id: provider.id, data: toRequest(form) },
      {
        onSuccess: () => {
          toast.success("Provider saved.");
          qc.invalidateQueries({ queryKey: getGetIdentityProviderQueryKey(provider.id) });
          qc.invalidateQueries({ queryKey: getListIdentityProvidersQueryKey() });
        },
        onError: (err) => setError(errorMessage(err)),
      },
    );
  };

  return (
    <div className="space-y-6">
      <form onSubmit={save} className="space-y-4">
        <ProviderFields form={form} onChange={setForm} hasSecret={provider.hasSecret} secretEnv={provider.secretEnv} />
        {provider.type === "oidc" && (
          <p className="text-sm text-muted-foreground">
            Register this redirect URI at the identity provider:{" "}
            <span className="font-mono text-foreground">
              {window.location.origin}/login/oauth2/code/{provider.id}
            </span>
          </p>
        )}
        {error && (
          <p role="alert" className="text-sm text-destructive">
            {error}
          </p>
        )}
        <div className="flex flex-wrap gap-2">
          <Button type="submit" disabled={!dirty || update.isPending} title={!dirty ? "No changes to save" : undefined}>
            Save changes
          </Button>
          <Button type="button" variant="outline" disabled={!dirty} onClick={() => setForm(initial)}>
            Discard
          </Button>
          <Button type="button" variant="ghost" className="ml-auto text-destructive" onClick={() => setDeleting(true)}>
            <Trash2 aria-hidden="true" />
            Delete provider
          </Button>
        </div>
      </form>

      <TestPanel provider={provider} dirty={dirty} />

      <ConfirmDeleteDialog
        open={deleting}
        onOpenChange={setDeleting}
        title={`Delete ${provider.name}?`}
        description="Only possible if nobody has signed in through it yet; otherwise disable it."
        confirmText={provider.name}
        onConfirm={() =>
          remove.mutate(
            { id: provider.id },
            {
              onSuccess: () => {
                qc.invalidateQueries({ queryKey: getListIdentityProvidersQueryKey() });
                navigate("/admin/identity-providers", { replace: true });
              },
              onError: (err) => {
                setDeleting(false);
                toast.error(errorMessage(err));
              },
            },
          )
        }
      />
    </div>
  );
}

function ProviderFields({
  form,
  onChange,
  hasSecret,
  secretEnv,
}: {
  form: FormState;
  onChange: (next: FormState) => void;
  hasSecret: boolean;
  secretEnv?: string | null;
}) {
  const nameId = useId();
  const enabledId = useId();
  const secretId = useId();
  const set = (key: string, value: string | boolean) => onChange({ ...form, values: { ...form.values, [key]: value } });
  const secretHelp = hasSecret
    ? `Stored${secretEnv ? ` as ${secretEnv}` : " encrypted"}. Leave empty to keep it.`
    : "Saved encrypted. Or type env:NAME to read it from the server's environment.";

  return (
    <ConfigTable valueClassName="w-80" detailsClassName="max-w-prose">
      <ConfigTableGroup label="Provider" />
      <ConfigTableRow label="Name" htmlFor={nameId} details="Shown on the sign-in page">
        <Input id={nameId} value={form.name} required maxLength={120} onChange={(e) => onChange({ ...form, name: e.target.value })} />
      </ConfigTableRow>
      <ConfigTableRow label="Enabled" htmlFor={enabledId} details="Offer it on the sign-in page">
        <Switch id={enabledId} checked={form.enabled} onCheckedChange={(v) => onChange({ ...form, enabled: v })} />
      </ConfigTableRow>
      <ConfigTableGroup label={form.type === "ldap" ? "Directory" : "OpenID Connect"} />
      {FIELDS[form.type].map((field) => {
        const inputId = `${form.type}-${field.key}`;
        return (
          <ConfigTableRow key={field.key} label={field.label} htmlFor={inputId} details={field.details}>
            {field.kind === "boolean" ? (
              <Switch id={inputId} checked={form.values[field.key] === true} onCheckedChange={(v) => set(field.key, v)} />
            ) : (
              <Input
                id={inputId}
                className="font-mono text-xs"
                required={field.required}
                placeholder={field.placeholder}
                value={String(form.values[field.key] ?? "")}
                onChange={(e) => set(field.key, e.target.value)}
              />
            )}
          </ConfigTableRow>
        );
      })}
      <ConfigTableRow label={SECRET_LABEL[form.type]} htmlFor={secretId} details={secretHelp}>
        <Input
          id={secretId}
          type="password"
          autoComplete="new-password"
          placeholder={hasSecret ? "••••••••" : undefined}
          value={form.secret}
          onChange={(e) => onChange({ ...form, secret: e.target.value })}
        />
      </ConfigTableRow>
    </ConfigTable>
  );
}

function TestPanel({ provider, dirty }: { provider: ProviderView; dirty: boolean }) {
  const test = useTestIdentityProvider();
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [result, setResult] = useState<TestResult | null>(null);
  const userId = useId();
  const passId = useId();

  const run = (e: FormEvent) => {
    e.preventDefault();
    setResult(null);
    test.mutate(
      { id: provider.id, data: provider.type === "ldap" && username ? { username, password } : {} },
      {
        onSuccess: setResult,
        onError: (err) => setResult({ ok: false, messages: [errorMessage(err)] }),
      },
    );
  };

  return (
    <section className="space-y-3 rounded-md border p-4" aria-labelledby="test-heading">
      <h2 id="test-heading" className="text-base font-semibold">
        Test the connection
      </h2>
      <form onSubmit={run} className="flex flex-wrap items-end gap-3">
        {provider.type === "ldap" && (
          <>
            <div className="space-y-1">
              <Label htmlFor={userId}>Test username (optional)</Label>
              <Input id={userId} value={username} autoComplete="off" onChange={(e) => setUsername(e.target.value)} />
            </div>
            <div className="space-y-1">
              <Label htmlFor={passId}>Password</Label>
              <Input id={passId} type="password" autoComplete="off" value={password} onChange={(e) => setPassword(e.target.value)} />
            </div>
          </>
        )}
        <Button type="submit" variant="outline" disabled={dirty || test.isPending} title={dirty ? "Save your changes first" : undefined}>
          Run test
        </Button>
        {dirty && <span className="text-xs text-muted-foreground">Tests use the saved settings. Save first.</span>}
      </form>
      {result && (
        <Alert variant={result.ok ? "default" : "destructive"}>
          <AlertDescription>
            <p className="font-medium">{result.ok ? "Test passed" : "Test failed"}</p>
            <ul className="mt-1 list-disc pl-5 text-sm">
              {result.messages.map((m) => (
                <li key={m} className="break-words">
                  {m}
                </li>
              ))}
            </ul>
          </AlertDescription>
        </Alert>
      )}
    </section>
  );
}

const SYSTEM = "__system__";
const MAPPING_ROLES: MappingViewRole[] = ["viewer", "author", "script_author", "admin", "owner"];

function MappingsTab({ provider }: { provider: ProviderView }) {
  const query = useGetGroupMappings(provider.id);
  return (
    <QueryBoundary query={query} what="group mappings">
      {(mappings) => <MappingsEditor key={JSON.stringify(mappings)} provider={provider} initial={mappings} />}
    </QueryBoundary>
  );
}

function MappingsEditor({ provider, initial }: { provider: ProviderView; initial: MappingView[] }) {
  const qc = useQueryClient();
  const workspaces = useListWorkspaces();
  const save = useReplaceGroupMappings();
  const [rows, setRows] = useState<MappingView[]>(initial);
  const [error, setError] = useState<string | null>(null);
  const dirty = JSON.stringify(rows) !== JSON.stringify(initial);
  useUnsavedChangesWarning(dirty);
  const activeWorkspaces = (workspaces.data ?? []).filter((w) => w.status === "active");

  const setRow = (i: number, patch: Partial<MappingView>) =>
    setRows(rows.map((r, j) => (j === i ? { ...r, ...patch } : r)));

  const submit = () => {
    setError(null);
    save.mutate(
      { id: provider.id, data: rows.map((r) => ({ ...r, externalGroup: r.externalGroup.trim() })) },
      {
        onSuccess: () => {
          toast.success("Mappings saved. They apply at each user's next sign-in.");
          qc.invalidateQueries({ queryKey: getGetGroupMappingsQueryKey(provider.id) });
        },
        onError: (err) => setError(errorMessage(err)),
      },
    );
  };

  return (
    <div className="space-y-4">
      <p className="max-w-prose text-sm text-muted-foreground">
        At every sign-in, a user&apos;s {provider.type === "ldap" ? "directory groups" : "groups claim"} decide their roles.
        Roles granted by hand stay unless the mapping is <em>managed</em>, in which case the groups alone decide.
      </p>
      <div className="overflow-x-auto rounded-md border">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Group</TableHead>
              <TableHead>Grants</TableHead>
              <TableHead>Role</TableHead>
              <TableHead>Managed</TableHead>
              <TableHead className="w-10"><span className="sr-only">Remove</span></TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {rows.length === 0 && (
              <TableRow>
                <TableCell colSpan={5} className="text-center text-sm text-muted-foreground">
                  No mappings: users from this provider get no workspace roles until someone adds them by hand.
                </TableCell>
              </TableRow>
            )}
            {rows.map((row, i) => {
              const system = row.role === "system_admin";
              return (
                <TableRow key={i}>
                  <TableCell>
                    <Input
                      aria-label={`Group name, row ${i + 1}`}
                      value={row.externalGroup}
                      placeholder="sales-authors"
                      className="w-56"
                      onChange={(e) => setRow(i, { externalGroup: e.target.value })}
                    />
                  </TableCell>
                  <TableCell>
                    <Select
                      value={system ? SYSTEM : (row.workspace ?? "")}
                      onValueChange={(v) =>
                        v === SYSTEM
                          ? setRow(i, { workspace: null, role: "system_admin", managed: false })
                          : setRow(i, { workspace: v, role: system ? "author" : row.role })
                      }
                    >
                      <SelectTrigger className="w-56" aria-label={`Grants, row ${i + 1}`}>
                        <SelectValue placeholder="Choose a workspace" />
                      </SelectTrigger>
                      <SelectContent>
                        <SelectItem value={SYSTEM}>System admin</SelectItem>
                        {activeWorkspaces.map((w) => (
                          <SelectItem key={w.id} value={w.slug}>
                            Workspace: {w.name}
                          </SelectItem>
                        ))}
                      </SelectContent>
                    </Select>
                  </TableCell>
                  <TableCell>
                    {system ? (
                      <span className="text-sm text-muted-foreground">—</span>
                    ) : (
                      <Select value={row.role} onValueChange={(v) => setRow(i, { role: v as MappingViewRole })}>
                        <SelectTrigger className="w-40" aria-label={`Role, row ${i + 1}`}>
                          <SelectValue />
                        </SelectTrigger>
                        <SelectContent>
                          {MAPPING_ROLES.map((r) => (
                            <SelectItem key={r} value={r}>
                              {ROLE_LABEL[r as keyof typeof ROLE_LABEL]}
                            </SelectItem>
                          ))}
                        </SelectContent>
                      </Select>
                    )}
                  </TableCell>
                  <TableCell>
                    <Checkbox
                      aria-label={`Managed, row ${i + 1}`}
                      checked={row.managed}
                      disabled={system}
                      onCheckedChange={(v) => setRow(i, { managed: v === true })}
                    />
                  </TableCell>
                  <TableCell>
                    <Button
                      variant="ghost"
                      size="icon"
                      aria-label={`Remove row ${i + 1}`}
                      onClick={() => setRows(rows.filter((_, j) => j !== i))}
                    >
                      <Trash2 className="h-4 w-4" />
                    </Button>
                  </TableCell>
                </TableRow>
              );
            })}
          </TableBody>
        </Table>
      </div>
      {error && (
        <p role="alert" className="text-sm text-destructive">
          {error}
        </p>
      )}
      <div className="flex flex-wrap gap-2">
        <Button
          variant="outline"
          onClick={() => setRows([...rows, { externalGroup: "", workspace: null, role: "author", managed: false }])}
        >
          <Plus aria-hidden="true" />
          Add mapping
        </Button>
        <Button onClick={submit} disabled={!dirty || save.isPending} title={!dirty ? "No changes to save" : undefined}>
          Save mappings
        </Button>
        <Button variant="ghost" disabled={!dirty} onClick={() => setRows(initial)}>
          Discard
        </Button>
      </div>
    </div>
  );
}
