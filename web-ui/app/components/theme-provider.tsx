import { createContext, useContext, useEffect, useState } from "react";

export type ThemeMode = "dark" | "light" | "system";
export type Theme = ThemeMode;
export type ColorTheme = "default" | "claude" | "t3-chat" | "mono" | "bubblegum";

export const COLOR_THEMES: ColorTheme[] = ["default", "claude", "t3-chat", "mono", "bubblegum"];

const COLOR_THEME_STORAGE_SUFFIX = "-color";

type ThemeProviderProps = {
  children: React.ReactNode;
  defaultTheme?: ThemeMode;
  defaultColorTheme?: ColorTheme;
  storageKey?: string;
};

type ThemeProviderState = {
  theme: ThemeMode;
  setTheme: (theme: ThemeMode) => void;
  colorTheme: ColorTheme;
  setColorTheme: (theme: ColorTheme) => void;
};

const initialState: ThemeProviderState = {
  theme: "system",
  colorTheme: "default",
  setTheme: () => null,
  setColorTheme: () => null,
};

const ThemeProviderContext = createContext<ThemeProviderState>(initialState);

function isThemeMode(value: string | null): value is ThemeMode {
  return value === "light" || value === "dark" || value === "system";
}

function isColorTheme(value: string | null): value is ColorTheme {
  return !!value && COLOR_THEMES.includes(value as ColorTheme);
}

export function ThemeProvider({
  children,
  defaultTheme = "system",
  defaultColorTheme = "default",
  storageKey = "vite-ui-theme",
  ...props
}: ThemeProviderProps) {
  const colorThemeStorageKey = `${storageKey}${COLOR_THEME_STORAGE_SUFFIX}`;

  const [theme, setTheme] = useState<ThemeMode>(() => {
    const stored = localStorage.getItem(storageKey);
    return isThemeMode(stored) ? stored : defaultTheme;
  });

  const [colorTheme, setColorTheme] = useState<ColorTheme>(() => {
    const stored = localStorage.getItem(colorThemeStorageKey);
    return isColorTheme(stored) ? stored : defaultColorTheme;
  });

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

  const value = {
    theme,
    colorTheme,
    setTheme: (theme: ThemeMode) => {
      localStorage.setItem(storageKey, theme);
      setTheme(theme);
    },
    setColorTheme: (theme: ColorTheme) => {
      localStorage.setItem(colorThemeStorageKey, theme);
      setColorTheme(theme);
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
