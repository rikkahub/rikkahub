export function PickerErrorAlert({ error }: { error: string | null }) {
  if (!error) return null;
  return (
    <div className="rounded-md border border-destructive/30 bg-destructive/10 px-3 py-2 text-xs text-destructive">
      {error}
    </div>
  );
}
