import * as React from "react";

export interface WorkbenchPanel {
  type: string;
  title: string;
  payload: Record<string, unknown>;
}

interface WorkbenchContextValue {
  panel: WorkbenchPanel | null;
  openPanel: (panel: WorkbenchPanel) => void;
  closePanel: () => void;
}

const WorkbenchContext = React.createContext<WorkbenchContextValue | null>(null);

export function useWorkbenchController(): WorkbenchContextValue {
  const [panel, setPanel] = React.useState<WorkbenchPanel | null>(null);

  const openPanel = React.useCallback((nextPanel: WorkbenchPanel) => {
    setPanel(nextPanel);
  }, []);

  const closePanel = React.useCallback(() => {
    setPanel(null);
  }, []);

  return React.useMemo(
    () => ({
      panel,
      openPanel,
      closePanel,
    }),
    [closePanel, openPanel, panel],
  );
}

export function WorkbenchProvider({
  value,
  children,
}: {
  value: WorkbenchContextValue;
  children: React.ReactNode;
}) {
  return <WorkbenchContext.Provider value={value}>{children}</WorkbenchContext.Provider>;
}

export function useWorkbench(): WorkbenchContextValue {
  const context = React.useContext(WorkbenchContext);
  if (!context) {
    throw new Error("useWorkbench must be used inside WorkbenchProvider");
  }
  return context;
}

export function useOptionalWorkbench(): WorkbenchContextValue | null {
  return React.useContext(WorkbenchContext);
}
