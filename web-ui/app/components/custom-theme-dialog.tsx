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
  const [lightDraft, setLightDraft] = React.useState(initialCss.light);
  const [darkDraft, setDarkDraft] = React.useState(initialCss.dark);

  React.useEffect(() => {
    if (!open) {
      return;
    }

    setLightDraft(initialCss.light);
    setDarkDraft(initialCss.dark);
  }, [initialCss.dark, initialCss.light, open]);

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-h-[85svh] max-w-3xl overflow-y-auto">
        <DialogHeader>
          <DialogTitle>{t("custom_theme_dialog.title")}</DialogTitle>
          <DialogDescription>{t("custom_theme_dialog.description")}</DialogDescription>
        </DialogHeader>

        <div className="space-y-4">
          <div className="space-y-2">
            <div className="text-sm font-medium">{t("custom_theme_dialog.light_variables")}</div>
            <Textarea
              value={lightDraft}
              onChange={(event) => {
                setLightDraft(event.target.value);
              }}
              placeholder={t("custom_theme_dialog.light_placeholder")}
              rows={CUSTOM_THEME_EDITOR_ROWS}
              className="field-sizing-fixed h-56 max-h-56 overflow-y-auto font-mono text-xs"
            />
          </div>

          <div className="space-y-2">
            <div className="text-sm font-medium">{t("custom_theme_dialog.dark_variables")}</div>
            <Textarea
              value={darkDraft}
              onChange={(event) => {
                setDarkDraft(event.target.value);
              }}
              placeholder={t("custom_theme_dialog.dark_placeholder")}
              rows={CUSTOM_THEME_EDITOR_ROWS}
              className="field-sizing-fixed h-56 max-h-56 overflow-y-auto font-mono text-xs"
            />
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
              onSave({
                light: lightDraft,
                dark: darkDraft,
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
