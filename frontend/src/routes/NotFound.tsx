import { useTranslation } from "react-i18next";

import { StatePage } from "@/components/layout/StatePage";

export function NotFound() {
  const { t } = useTranslation();
  return (
    <StatePage eyebrow="404" title={t("Page not found")} link={{ to: "/", label: t("Go to my workspace") }}>
      <p>{t("The address may be mistyped, or the page was moved.")}</p>
    </StatePage>
  );
}
