import i18n from "i18next";
import { initReactI18next } from "react-i18next";

/**
 * UI languages (spec §17, FE-COPY). English text is the key, so English needs no dictionary
 * and a missing Vietnamese entry falls back to English instead of showing a key. Vietnamese
 * entries live in src/i18n/vi/*.json, one file per area of the UI.
 */
export const LANGUAGES = { en: "English", vi: "Tiếng Việt" } as const;
export type Language = keyof typeof LANGUAGES;

const STORAGE_KEY = "opendbapi.language";

const viFiles = import.meta.glob<{ default: Record<string, string> }>("./vi/*.json", { eager: true });
const vi: Record<string, string> = {};
for (const file of Object.values(viFiles)) {
  Object.assign(vi, file.default);
}

function stored(): Language {
  try {
    const v = localStorage.getItem(STORAGE_KEY);
    return v === "vi" ? "vi" : "en";
  } catch {
    return "en";
  }
}

void i18n.use(initReactI18next).init({
  resources: { en: { translation: {} }, vi: { translation: vi } },
  lng: stored(),
  fallbackLng: "en",
  keySeparator: false,
  nsSeparator: false,
  returnEmptyString: false,
  interpolation: { escapeValue: false },
});

export function setLanguage(language: Language) {
  void i18n.changeLanguage(language);
  document.documentElement.lang = language;
  try {
    localStorage.setItem(STORAGE_KEY, language);
  } catch {
    // private mode: the choice lasts for this page only
  }
}

if (typeof document !== "undefined") {
  document.documentElement.lang = i18n.language;
}

export default i18n;
