import * as React from "react";
import { useTranslation } from "react-i18next";

import { extractErrorMessage } from "~/lib/error";
import { onWebAuthRequired, requestWebAuthToken } from "~/services/api";
import { Button } from "~/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "~/components/ui/card";
import { Input } from "~/components/ui/input";

export function WebAuthGate() {
  const { t } = useTranslation();
  const [open, setOpen] = React.useState(false);
  const [password, setPassword] = React.useState("");
  const [submitting, setSubmitting] = React.useState(false);
  const [error, setError] = React.useState<string | null>(null);
  const inputRef = React.useRef<HTMLInputElement | null>(null);

  React.useEffect(() => {
    return onWebAuthRequired(() => {
      setOpen(true);
      setSubmitting(false);
      setError(null);
      setPassword("");
    });
  }, []);

  React.useEffect(() => {
    if (!open) return;
    inputRef.current?.focus();
  }, [open]);

  const onSubmit = React.useCallback(
    async (event: React.FormEvent<HTMLFormElement>) => {
      event.preventDefault();
      if (password.length === 0) {
        setError(t("web_auth_gate.password_required"));
        return;
      }

      setSubmitting(true);
      setError(null);
      try {
        await requestWebAuthToken(password);
        setOpen(false);
        window.location.reload();
      } catch (submitError) {
        setError(extractErrorMessage(submitError, t("web_auth_gate.unlock_failed")));
      } finally {
        setSubmitting(false);
      }
    },
    [password, t],
  );

  if (!open) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/45 p-4 backdrop-blur-[2px]">
      <Card className="w-full max-w-sm">
        <CardHeader>
          <CardTitle>{t("web_auth_gate.title")}</CardTitle>
          <CardDescription>{t("web_auth_gate.description")}</CardDescription>
        </CardHeader>
        <CardContent>
          <form className="space-y-3" onSubmit={onSubmit}>
            <Input
              ref={inputRef}
              type="password"
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              placeholder={t("web_auth_gate.password_placeholder")}
              autoComplete="current-password"
              disabled={submitting}
            />
            {error ? <p className="text-sm text-destructive">{error}</p> : null}
            <Button className="w-full" type="submit" disabled={submitting}>
              {submitting ? t("web_auth_gate.unlocking") : t("web_auth_gate.unlock")}
            </Button>
          </form>
        </CardContent>
      </Card>
    </div>
  );
}
