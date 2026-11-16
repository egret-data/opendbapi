import { StatePage } from "@/components/layout/StatePage";

export function NotFound() {
  return (
    <StatePage eyebrow="404" title="Page not found" link={{ to: "/", label: "Go to my workspace" }}>
      <p>The address may be mistyped, or the page was moved.</p>
    </StatePage>
  );
}
