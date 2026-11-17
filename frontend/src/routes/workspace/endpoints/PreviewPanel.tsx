import { useState } from "react";
import { Play } from "lucide-react";

import { usePreviewEndpoint } from "@/api/generated/endpoints/endpoints";
import type { Finding, PreviewResponse } from "@/api/generated/model";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { errorMessage } from "@/lib/errors";
import type { DraftForm } from "./endpointForm";

/**
 * Runs what is on screen, saved or not, with test values (spec §11.1). The server rolls the
 * transaction back, so previewing a write changes nothing.
 */
export function PreviewPanel({ ws, id, form }: { ws: string; id: string; form: DraftForm }) {
  const preview = usePreviewEndpoint();
  const [values, setValues] = useState<Record<string, string>>({});
  const [result, setResult] = useState<PreviewResponse | null>(null);
  const [error, setError] = useState<string | null>(null);
  const params = form.definition.params;

  const run = () => {
    setError(null);
    const typed: Record<string, unknown> = {};
    for (const p of params) {
      const v = values[p.name];
      if (v === undefined || v === "") continue;
      typed[p.name] = p.type.startsWith("array<") ? v.split(",").map((s) => s.trim()).filter(Boolean) : v;
    }
    preview.mutate(
      { ws, id, data: { method: form.method, path: form.path, definition: form.definition, params: typed } },
      { onSuccess: setResult, onError: (err) => { setResult(null); setError(errorMessage(err)); } },
    );
  };

  return (
    <section aria-labelledby="preview-heading" className="space-y-3 rounded-md border p-4">
      <div className="flex flex-wrap items-end gap-3">
        <h3 id="preview-heading" className="mr-2 text-sm font-semibold">Preview</h3>
        {params.map((p) => (
          <div key={p.name} className="space-y-1">
            <Label htmlFor={`pv-${p.name}`} className="font-mono text-xs">{p.name || "(unnamed)"}</Label>
            <Input id={`pv-${p.name}`} className="h-8 w-36 text-xs" placeholder={p.type.startsWith("array<") ? "a, b, c" : p.type}
              value={values[p.name] ?? ""} onChange={(e) => setValues({ ...values, [p.name]: e.target.value })} />
          </div>
        ))}
        <Button type="button" size="sm" onClick={run} disabled={preview.isPending}>
          <Play aria-hidden="true" />
          {preview.isPending ? "Running…" : "Run"}
        </Button>
        {result && (
          <span className="text-xs text-muted-foreground tabular-nums">
            {result.durationMs} ms · {result.affectedRows != null ? `${result.affectedRows} rows affected (rolled back)` : `${result.rows.length} ${result.rows.length === 1 ? "row" : "rows"}`}
            {result.truncated ? " · first 100 shown" : ""}
          </span>
        )}
      </div>
      {error && <Alert variant="destructive"><AlertDescription className="break-words">{error}</AlertDescription></Alert>}
      {result && (
        <Tabs defaultValue="result">
          <TabsList variant="underline">
            <TabsTrigger value="result">Result</TabsTrigger>
            <TabsTrigger value="sql">SQL & binds</TabsTrigger>
            <TabsTrigger value="checks">Warnings{result.findings.length ? ` (${result.findings.length})` : ""}</TabsTrigger>
            <TabsTrigger value="schema">Output schema</TabsTrigger>
          </TabsList>
          <TabsContent value="result" className="pt-3">
            {result.affectedRows != null ? (
              <p className="text-sm">{result.affectedRows} rows would be affected. Nothing was saved.</p>
            ) : result.rows.length === 0 ? (
              <p className="text-sm text-muted-foreground">No rows.</p>
            ) : (
              <div className="max-h-80 overflow-auto rounded-md border">
                <Table>
                  <TableHeader>
                    <TableRow>{result.columns.map((c) => <TableHead key={c.name} title={c.type} className="font-mono text-xs">{c.name}</TableHead>)}</TableRow>
                  </TableHeader>
                  <TableBody>
                    {result.rows.map((row, i) => (
                      <TableRow key={i}>
                        {result.columns.map((c) => (
                          <TableCell key={c.name} className="font-mono text-xs">{format((row as Record<string, unknown>)[c.name])}</TableCell>
                        ))}
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </div>
            )}
          </TabsContent>
          <TabsContent value="sql" className="space-y-3 pt-3">
            {result.executed.map((e, i) => (
              <div key={i} className="space-y-1">
                <pre className="overflow-x-auto rounded-md bg-muted p-3 font-mono text-xs whitespace-pre-wrap">{e.sql}</pre>
                <p className="font-mono text-xs text-muted-foreground">binds: {JSON.stringify(e.parameters)}</p>
              </div>
            ))}
          </TabsContent>
          <TabsContent value="schema" className="pt-3">
            {result.columns.length === 0 ? (
              <p className="text-sm text-muted-foreground">No columns: the statement does not return rows.</p>
            ) : (
              <div className="max-w-xl overflow-x-auto rounded-md border">
                <Table>
                  <TableHeader>
                    <TableRow><TableHead>Column</TableHead><TableHead>Database type</TableHead></TableRow>
                  </TableHeader>
                  <TableBody>
                    {result.columns.map((c) => (
                      <TableRow key={c.name}>
                        <TableCell className="font-mono text-xs">{c.name}</TableCell>
                        <TableCell className="font-mono text-xs">{c.type}</TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </div>
            )}
            <p className="mt-2 text-xs text-muted-foreground">Publishing freezes these columns into the version's API documentation.</p>
          </TabsContent>
          <TabsContent value="checks" className="pt-3">
            <FindingsList findings={result.findings} />
          </TabsContent>
        </Tabs>
      )}
    </section>
  );
}

export function FindingsList({ findings }: { findings: Finding[] }) {
  if (findings.length === 0) return <p className="text-sm text-muted-foreground">No problems found.</p>;
  return (
    <ul className="space-y-1 text-sm">
      {findings.map((f, i) => (
        <li key={i} className={f.severity === "error" ? "text-destructive" : "text-foreground"}>
          <span className="font-medium">{f.severity === "error" ? "Error" : "Warning"}:</span> {f.message}
        </li>
      ))}
    </ul>
  );
}

function format(v: unknown): string {
  if (v === null || v === undefined) return "NULL";
  return typeof v === "object" ? JSON.stringify(v) : String(v);
}
