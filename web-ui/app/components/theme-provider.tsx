import { createContext, useContext, useEffect, useState } from "react";

export type ThemeMode = "dark" | "light" | "system";
export type Theme = ThemeMode;
export type ColorTheme = "default" | "claude" | "t3-chat" | "mono" | "bubblegum" | "custom";

export const COLOR_THEMES: ColorTheme[] = [
  "default",
  "claude",
  "t3-chat",
  "mono",
  "bubblegum",
  "custom",
];

const COLOR_THEME_STORAGE_SUFFIX = "-color";
const CUSTOM_THEME_LIGHT_STORAGE_SUFFIX = "-custom-light";
const CUSTOM_THEME_DARK_STORAGE_SUFFIX = "-custom-dark";
const CUSTOM_THEME_STYLE_ID = "rikkahub-custom-theme";

type ThemeProviderProps = {
  children: React.ReactNode;
  defaultTheme?: ThemeMode;
  defaultColorTheme?: ColorTheme;
  storageKey?: string;
};

export type CustomThemeCss = {
  light: string;
  dark: string;
};

type ThemeProviderState = {
  theme: ThemeMode;
  setTheme: (theme: ThemeMode) => void;
  colorTheme: ColorTheme;
  setColorTheme: (theme: ColorTheme) => void;
  customThemeCss: CustomThemeCss;
  setCustomThemeCss: (theme: CustomThemeCss) => void;
};

const initialState: ThemeProviderState = {
  theme: "system",
  colorTheme: "default",
  customThemeCss: {
    light: "",
    dark: "",
  },
  setTheme: () => null,
  setColorTheme: () => null,
  setCustomThemeCss: () => null,
};

const ThemeProviderContext = createContext<ThemeProviderState>(initialState);

function isThemeMode(value: string | null): value is ThemeMode {
  return value === "light" || value === "dark" || value === "system";
}

function isColorTheme(value: string | null): value is ColorTheme {
  return !!value && COLOR_THEMES.includes(value as ColorTheme);
}

function sanitizeCustomThemeCss(value: string, mode: "light" | "dark" = "light"): string {
  if (!value.trim()) {
    return "";
  }

  // 尝试分别提取浅色和深色主题的变量
  if (mode === "light") {
    // 提取 :root 选择器下的变量
    const rootMatch = value.match(/:root\s*\{([\s\S]*?)\}/);
    if (rootMatch) {
      const rootVars = rootMatch[1].match(/--[a-zA-Z0-9-_]+\s*:\s*[^;{}]+;/g);
      if (rootVars) {
        return rootVars.map((declaration) => declaration.trim()).join("\n  ");
      }
    }
  } else {
    // 提取 .dark 或 :root.dark 选择器下的变量
    const darkMatch = value.match(/(?:\.dark|:root\.dark)\s*\{([\s\S]*?)\}/);
    if (darkMatch) {
      const darkVars = darkMatch[1].match(/--[a-zA-Z0-9-_]+\s*:\s*[^;{}]+;/g);
      if (darkVars) {
        return darkVars.map((declaration) => declaration.trim()).join("\n  ");
      }
    }
  }

  // 如果没有找到对应的选择器，回退到提取所有变量
  const allMatches = value.match(/--[a-zA-Z0-9-_]+\s*:\s*[^;{}]+;/g);
  if (allMatches) {
    return allMatches.map((declaration) => declaration.trim()).join("\n  ");
  }

  return "";
}

export function ThemeProvider({
  children,
  defaultTheme = "system",
  defaultColorTheme = "default",
  storageKey = "vite-ui-theme",
  ...props
}: ThemeProviderProps) {
  const colorThemeStorageKey = `${storageKey}${COLOR_THEME_STORAGE_SUFFIX}`;
  const customThemeLightStorageKey = `${storageKey}${CUSTOM_THEME_LIGHT_STORAGE_SUFFIX}`;
  const customThemeDarkStorageKey = `${storageKey}${CUSTOM_THEME_DARK_STORAGE_SUFFIX}`;

  const [theme, setTheme] = useState<ThemeMode>(() => {
    const stored = localStorage.getItem(storageKey);
    return isThemeMode(stored) ? stored : defaultTheme;
  });

  const [colorTheme, setColorTheme] = useState<ColorTheme>(() => {
    const stored = localStorage.getItem(colorThemeStorageKey);
    return isColorTheme(stored) ? stored : defaultColorTheme;
  });

  const [customThemeCss, setCustomThemeCss] = useState<CustomThemeCss>(() => ({
    light: localStorage.getItem(customThemeLightStorageKey) ?? "",
    dark: localStorage.getItem(customThemeDarkStorageKey) ?? "",
  }));

  useEffect(() => {
    const root = window.document.documentElement;
    const mediaQuery = window.matchMedia("(prefers-color-scheme: dark)");

    const applyMode = (mode: ThemeMode) => {
      root.classList.remove("light", "dark");

      if (mode === "system") {
        root.classList.add(mediaQuery.matches ? "dark" : "light");
        return;
      }

      root.classList.add(mode);
    };

    applyMode(theme);

    if (theme !== "system") {
      return;
    }

    const onSystemThemeChange = () => {
      applyMode("system");
    };

    mediaQuery.addEventListener("change", onSystemThemeChange);

    return () => {
      mediaQuery.removeEventListener("change", onSystemThemeChange);
    };
  }, [theme]);

  useEffect(() => {
    const root = window.document.documentElement;
    root.dataset.theme = colorTheme;
  }, [colorTheme]);

  useEffect(() => {
    const lightCss = sanitizeCustomThemeCss(customThemeCss.light, "light");
    const darkCss = sanitizeCustomThemeCss(customThemeCss.dark, "dark");
    const cssBlocks = [
      lightCss ? `:root[data-theme="custom"] {\n  ${lightCss}\n}` : "",
      darkCss ? `:root.dark[data-theme="custom"] {\n  ${darkCss}\n}` : "",
    ]
      .filter(Boolean)
      .join("\n\n");

    const existingStyle = document.getElementById(CUSTOM_THEME_STYLE_ID);

    if (!cssBlocks) {
      existingStyle?.remove();
      return;
    }

    const styleElement = existingStyle ?? document.createElement("style");
    styleElement.id = CUSTOM_THEME_STYLE_ID;
    styleElement.textContent = cssBlocks;

    if (!existingStyle) {
      document.head.appendChild(styleElement);
    }
  }, [customThemeCss.dark, customThemeCss.light]);

  const value = {
    theme,
    colorTheme,
    customThemeCss,
    setTheme: (theme: ThemeMode) => {
      localStorage.setItem(storageKey, theme);
      setTheme(theme);
    },
    setColorTheme: (theme: ColorTheme) => {
      localStorage.setItem(colorThemeStorageKey, theme);
      setColorTheme(theme);
    },
    setCustomThemeCss: (themeCss: CustomThemeCss) => {
      localStorage.setItem(customThemeLightStorageKey, themeCss.light);
      localStorage.setItem(customThemeDarkStorageKey, themeCss.dark);
      setCustomThemeCss(themeCss);
    },
  };

  return (
    <ThemeProviderContext.Provider {...props} value={value}>
      {children}
    </ThemeProviderContext.Provider>
  );
}

export const useTheme = () => {
  const context = useContext(ThemeProviderContext);

  if (context === undefined) throw new Error("useTheme must be used within a ThemeProvider");

  return context;
};
