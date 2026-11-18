import { Plus, Trash2, Wand2 } from "lucide-react";
import { useTranslation } from "react-i18next";

import type { Param, ParamIn, ParamType } from "@/api/generated/model";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { Input } from "@/components/ui/input";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { PARAM_TYPES, detectParams, newParam } from "./endpointForm";

const IN_OPTIONS: ParamIn[] = ["query", "path", "header", "body"];

/** The parameters table of the Query tab (spec §5.3). */
export function ParamsEditor({
  params,
  onChange,
  sql,
  path,
  disabled,
  ignore,
  http = false,
}: {
  params: Param[];
  onChange: (next: Param[]) => void;
  sql: string;
  path: string;
  disabled: boolean;
  /** Names that are not parameters, e.g. workflow node ids. */
  ignore?: string[];
  /** The names come from an HTTP request rather than SQL. */
  http?: boolean;
}) {
  const { t } = useTranslation();
  const set = (i: number, patch: Partial<Param>) => onChange(params.map((p, j) => (j === i ? { ...p, ...patch } : p)));
  const missing = detectParams(sql).filter((n) => !params.some((p) => p.name === n) && !(ignore ?? []).includes(n));

  return (
    <div className="space-y-2">
      <div className="flex items-center justify-between gap-2">
        <h3 className="text-sm font-semibold">{t("Parameters")}</h3>
        {!disabled && (
          <div className="flex gap-1">
            <Button type="button" variant="ghost" size="sm" disabled={missing.length === 0}
              title={missing.length === 0
                ? (http ? t("Every name the request reads is declared") : t("Every name the SQL reads is declared"))
                : t("Add {{names}}", { names: missing.join(", ") })}
              onClick={() => onChange([...params, ...missing.map((n) => newParam(n, path))])}>
              <Wand2 aria-hidden="true" />
              {http ? t("Detect from request") : t("Detect from SQL")}
            </Button>
            <Button type="button" variant="ghost" size="sm" onClick={() => onChange([...params, newParam("", path)])}>
              <Plus aria-hidden="true" />
              {t("Add")}
            </Button>
          </div>
        )}
      </div>
      {params.length === 0 ? (
        <p className="text-sm text-muted-foreground">
          {t("No parameters. Write")} <span className="font-mono">#{"{name}"}</span> {t("in the SQL (or")}{" "}
          <span className="font-mono">params.name</span> {t("in a script), then use Detect from SQL.")}
        </p>
      ) : (
        <div className="overflow-x-auto rounded-md border">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{t("Name")}</TableHead>
                <TableHead>{t("In")}</TableHead>
                <TableHead>{t("Type")}</TableHead>
                <TableHead>{t("Required")}</TableHead>
                <TableHead>{t("Default")}</TableHead>
                <TableHead className="w-10"><span className="sr-only">{t("Remove")}</span></TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {params.map((p, i) => (
                <TableRow key={i}>
                  <TableCell>
                    <Input aria-label={t("Parameter {{n}} name", { n: i + 1 })} className="w-32 font-mono text-xs" value={p.name} disabled={disabled}
                      onChange={(e) => set(i, { name: e.target.value.trim() })} />
                  </TableCell>
                  <TableCell>
                    <Select value={p.in} onValueChange={(v) => set(i, { in: v as ParamIn })} disabled={disabled}>
                      <SelectTrigger className="w-24" aria-label={t("Parameter {{n}} location", { n: i + 1 })}><SelectValue /></SelectTrigger>
                      <SelectContent>{IN_OPTIONS.map((o) => <SelectItem key={o} value={o}>{o}</SelectItem>)}</SelectContent>
                    </Select>
                  </TableCell>
                  <TableCell>
                    <Select value={p.type} onValueChange={(v) => set(i, { type: v as ParamType })} disabled={disabled}>
                      <SelectTrigger className="w-36" aria-label={t("Parameter {{n}} type", { n: i + 1 })}><SelectValue /></SelectTrigger>
                      <SelectContent>{PARAM_TYPES.map((type) => <SelectItem key={type} value={type}>{type}</SelectItem>)}</SelectContent>
                    </Select>
                  </TableCell>
                  <TableCell>
                    <Checkbox aria-label={t("Parameter {{n}} required", { n: i + 1 })} checked={p.required} disabled={disabled}
                      onCheckedChange={(v) => set(i, { required: v === true })} />
                  </TableCell>
                  <TableCell>
                    <Input aria-label={t("Parameter {{n}} default", { n: i + 1 })} className="w-28 text-xs" disabled={disabled || p.required}
                      value={p.defaultValue == null ? "" : String(p.defaultValue)}
                      onChange={(e) => set(i, { defaultValue: e.target.value === "" ? null : (e.target.value as never) })} />
                  </TableCell>
                  <TableCell>
                    {!disabled && (
                      <Button type="button" variant="ghost" size="icon" aria-label={t("Remove parameter {{n}}", { n: i + 1 })}
                        onClick={() => onChange(params.filter((_, j) => j !== i))}>
                        <Trash2 className="h-4 w-4" />
                      </Button>
                    )}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      )}
    </div>
  );
}
