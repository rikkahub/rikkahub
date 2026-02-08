import * as React from "react";

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
          <DialogTitle>自定义主题 CSS</DialogTitle>
          <DialogDescription>
            仅提取并应用 CSS 变量声明（例如 --primary: ...;），支持 Light 和 Dark 两套。
          </DialogDescription>
        </DialogHeader>

        <div className="space-y-4">
          <div className="space-y-2">
            <div className="text-sm font-medium">Light 变量</div>
            <Textarea
              value={lightDraft}
              onChange={(event) => {
                setLightDraft(event.target.value);
              }}
              placeholder=":root {\n  --background: ...;\n  --primary: ...;\n}"
              rows={CUSTOM_THEME_EDITOR_ROWS}
              className="field-sizing-fixed h-56 max-h-56 overflow-y-auto font-mono text-xs"
            />
          </div>

          <div className="space-y-2">
            <div className="text-sm font-medium">Dark 变量</div>
            <Textarea
              value={darkDraft}
              onChange={(event) => {
                setDarkDraft(event.target.value);
              }}
              placeholder=":root.dark {\n  --background: ...;\n  --primary: ...;\n}"
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
            取消
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
            保存并应用
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
