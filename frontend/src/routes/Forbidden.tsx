import { useTranslation } from "react-i18next";

import { StatePage } from "@/components/layout/StatePage";

/** A refusal, rendered inside the shell (FE-STATE-5). */
export function Forbidden({ reason }: { reason: string }) {
  const { t } = useTranslation();
  return (
    <StatePage eyebrow="403" title={t("You don't have access")} link={{ to: "/", label: t("Go to my workspace") }}>
      <p>{reason}</p>
    </StatePage>
  );
}
