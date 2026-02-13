import * as React from "react";

export function usePickerPopover(canUse: boolean) {
  const [open, setOpen] = React.useState(false);
  const [error, setError] = React.useState<string | null>(null);

  React.useEffect(() => {
    if (!open) setError(null);
  }, [open]);

  const popoverProps = {
    open,
    onOpenChange: (nextOpen: boolean) => {
      if (!canUse) {
        setOpen(false);
        return;
      }
      setOpen(nextOpen);
    },
  };

  return { open, setOpen, error, setError, popoverProps };
}
