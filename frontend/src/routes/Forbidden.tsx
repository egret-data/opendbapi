import { StatePage } from "@/components/layout/StatePage";

/** A refusal, rendered inside the shell (FE-STATE-5). */
export function Forbidden({ reason }: { reason: string }) {
  return (
    <StatePage eyebrow="403" title="You don't have access" link={{ to: "/", label: "Go to my workspace" }}>
      <p>{reason}</p>
    </StatePage>
  );
}
