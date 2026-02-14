import * as React from "react";
import { useTranslation } from "react-i18next";

import { Button } from "~/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "~/components/ui/dialog";
import { Textarea } from "~/components/ui/textarea";
import type { CustomThemeCss } from "~/components/theme-provider";

const CUSTOM_THEME_EDITOR_ROWS = 14;

type CustomThemeDialogProps = {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  initialCss: CustomThemeCss;
  onSave: (css: CustomThemeCss) => void;
};

export function CustomThemeDialog({
  open,
  onOpenChange,
  initialCss,
  onSave,
}: CustomThemeDialogProps) {
  const { t } = useTranslation();
  const [cssDraft, setCssDraft] = React.useState(initialCss.light || initialCss.dark || "");

  React.useEffect(() => {
    if (!open) {
      return;
    }

    setCssDraft(initialCss.light || initialCss.dark || "");
  }, [initialCss.light, initialCss.dark, open]);

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-h-[85svh] max-w-3xl overflow-y-auto">
        <DialogHeader>
          <DialogTitle>{t("custom_theme_dialog.title")}</DialogTitle>
          <DialogDescription>{t("custom_theme_dialog.description")}</DialogDescription>
        </DialogHeader>

        <div className="space-y-4">
          <div className="space-y-2">
            <div className="text-sm font-medium">{t("custom_theme_dialog.theme_variables")}</div>
            <Textarea
              value={cssDraft}
              onChange={(event) => {
                setCssDraft(event.target.value);
              }}
              placeholder={t("custom_theme_dialog.theme_placeholder")}
              rows={CUSTOM_THEME_EDITOR_ROWS}
              className="field-sizing-fixed h-56 max-h-56 overflow-y-auto font-mono text-xs"
            />
          </div>

          <div className="text-sm text-muted-foreground">
            {t("custom_theme_dialog.tip")}{" "}
            <a
              href="https://tweakcn.com/"
              target="_blank"
              rel="noopener noreferrer"
              className="text-primary hover:underline"
            >
              https://tweakcn.com/
            </a>
          </div>
        </div>

        <DialogFooter>
          <Button
            type="button"
            variant="outline"
            onClick={() => {
              onOpenChange(false);
            }}
          >
            {t("custom_theme_dialog.cancel")}
          </Button>
          <Button
            type="button"
            onClick={() => {
              // 分离浅色和深色主题变量
              let lightCss = "";
              let darkCss = "";

              // 提取 :root 选择器下的变量作为浅色主题
              const rootMatch = cssDraft.match(/:root\s*\{([\s\S]*?)\}/);
              if (rootMatch) {
                lightCss = rootMatch[1];
              }

              // 提取 .dark 或 :root.dark 选择器下的变量作为深色主题
              const darkMatch = cssDraft.match(/(?:\.dark|:root\.dark)\s*\{([\s\S]*?)\}/);
              if (darkMatch) {
                darkCss = darkMatch[1];
              }

              onSave({
                light: lightCss,
                dark: darkCss,
              });
              onOpenChange(false);
            }}
          >
            {t("custom_theme_dialog.save_and_apply")}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
