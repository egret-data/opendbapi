import { readFileSync, readdirSync, statSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

const SRC = join(__dirname, "..");

function files(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) return ["api", "__tests__", "i18n"].includes(name) ? [] : files(path);
    return /\.tsx?$/.test(name) ? [path] : [];
  });
}

/** Keys passed to t() as plain string literals ("…" or '…'). */
function keysIn(source: string): string[] {
  const keys: string[] = [];
  for (const m of source.matchAll(/\bt\(\s*"((?:[^"\\]|\\.)*)"/g)) keys.push(JSON.parse(`"${m[1]}"`));
  for (const m of source.matchAll(/\bt\(\s*'((?:[^'\\]|\\.)*)'/g)) keys.push(m[1].replace(/\\'/g, "'"));
  return keys;
}

function vietnamese(): Record<string, string> {
  const dir = join(SRC, "i18n", "vi");
  return Object.assign({}, ...readdirSync(dir).filter((f) => f.endsWith(".json"))
    .map((f) => JSON.parse(readFileSync(join(dir, f), "utf8")) as Record<string, string>));
}

describe("Vietnamese translation", () => {
  it("covers every text the UI passes to t()", () => {
    // Arrange
    const vi = vietnamese();
    const used = new Set(files(SRC).flatMap((f) => keysIn(readFileSync(f, "utf8"))));

    // Act
    const missing = [...used].filter((k) => !vi[k]?.trim());

    // Assert
    expect(missing).toEqual([]);
  });

  it("keeps the same {{placeholders}} as the English text", () => {
    // Arrange
    const vi = vietnamese();
    const names = (s: string) => [...s.matchAll(/\{\{\s*(\w+)\s*\}\}/g)].map((m) => m[1]).sort();

    // Act
    const broken = Object.entries(vi).filter(([en, v]) => names(en).join() !== names(v).join()).map(([en]) => en);

    // Assert
    expect(broken).toEqual([]);
  });
});
