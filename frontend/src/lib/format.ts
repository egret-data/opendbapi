import dayjs from "dayjs";

/** `2026-11-16` style dates; tabular and unambiguous across locales. */
export function formatDate(iso: string | null | undefined): string {
  return iso ? dayjs(iso).format("YYYY-MM-DD") : "—";
}

export function formatDateTime(iso: string | null | undefined): string {
  return iso ? dayjs(iso).format("YYYY-MM-DD HH:mm:ss") : "—";
}
