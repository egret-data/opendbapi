import { StatePage } from "@/components/layout/StatePage";

/** Placeholder for an area that a later roadmap step builds (spec §25). */
export function NotBuiltYet({ title, step, summary }: { title: string; step: number; summary: string }) {
  return (
    <StatePage eyebrow={`Roadmap step ${step}`} title={title} link={{ to: "/", label: "Go to my workspace" }}>
      <p>{summary}</p>
    </StatePage>
  );
}
