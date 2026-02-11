import i18n from "i18next";
import { initReactI18next } from "react-i18next";

import enUSCommon from "./locales/en-US/common.json";
import zhCNCommon from "./locales/zh-CN/common.json";

const SUPPORTED_LANGUAGES = ["zh-CN", "en-US"] as const;

function getInitialLanguage(): (typeof SUPPORTED_LANGUAGES)[number] {
  if (typeof window === "undefined") {
    return "zh-CN";
  }

  const fromStorage = window.localStorage.getItem("lang");
  if (fromStorage === "zh-CN" || fromStorage === "en-US") {
    return fromStorage;
  }

  const browserLanguage = window.navigator.language;
  return browserLanguage.startsWith("zh") ? "zh-CN" : "en-US";
}

void i18n.use(initReactI18next).init({
  resources: {
    "zh-CN": {
      common: zhCNCommon,
    },
    "en-US": {
      common: enUSCommon,
    },
  },
  lng: getInitialLanguage(),
  fallbackLng: "zh-CN",
  supportedLngs: [...SUPPORTED_LANGUAGES],
  defaultNS: "common",
  ns: ["common"],
  interpolation: {
    escapeValue: false,
  },
});

void i18n.on("languageChanged", (language) => {
  if (typeof window !== "undefined") {
    window.localStorage.setItem("lang", language);
  }
});

export default i18n;
