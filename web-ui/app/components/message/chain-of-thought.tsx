import * as React from "react";
import { ChevronDown, ChevronRight, ChevronUp } from "lucide-react";

import { Card } from "~/components/ui/card";
import { cn } from "~/lib/utils";

interface ChainOfThoughtProps<T> extends React.ComponentProps<typeof Card> {
  steps: T[];
  collapsedVisibleCount?: number;
  collapsedAdaptiveWidth?: boolean;
  renderStep: (
    step: T,
    index: number,
    info: { isFirst: boolean; isLast: boolean },
  ) => React.ReactNode;
  collapseLabel?: React.ReactNode;
  showMoreLabel?: (hiddenCount: number) => React.ReactNode;
}

interface ChainOfThoughtStepBaseProps {
  icon?: React.ReactNode;
  label: React.ReactNode;
  extra?: React.ReactNode;
  onClick?: () => void;
  children?: React.ReactNode;
  contentVisible?: boolean;
  collapsedAdaptiveWidth?: boolean;
  className?: string;
  isFirst?: boolean;
  isLast?: boolean;
}

interface ChainOfThoughtStepProps extends ChainOfThoughtStepBaseProps {
  defaultExpanded?: boolean;
}

interface ControlledChainOfThoughtStepProps extends ChainOfThoughtStepBaseProps {
  expanded: boolean;
  onExpandedChange: (expanded: boolean) => void;
}

function ChainOfThought<T>({
  steps,
  collapsedVisibleCount = 2,
  collapsedAdaptiveWidth = false,
  renderStep,
  collapseLabel = "Collapse",
  showMoreLabel,
  className,
  ...props
}: ChainOfThoughtProps<T>) {
  const [expanded, setExpanded] = React.useState(false);
  const canCollapse = steps.length > collapsedVisibleCount;
  const visibleSteps = expanded || !canCollapse ? steps : steps.slice(-collapsedVisibleCount);
  const hiddenCount = Math.max(steps.length - collapsedVisibleCount, 0);
  const shouldFillCollapseControlWidth = expanded || !collapsedAdaptiveWidth;

  return (
    <Card
      className={cn(
        "gap-0 border-muted bg-muted/50 px-2 py-2 shadow-none",
        collapsedAdaptiveWidth && !expanded && "w-fit max-w-full self-start",
        className,
      )}
      {...props}
    >
      {canCollapse && (
        <button
          type="button"
          className={cn(
            "text-primary hover:bg-muted/60 focus-visible:ring-ring/50 mb-1 flex items-center gap-2 rounded-md px-1 py-1 text-left text-sm outline-none focus-visible:ring-[3px]",
            shouldFillCollapseControlWidth ? "w-full" : "w-fit self-start",
          )}
          onClick={() => setExpanded((prev) => !prev)}
        >
          <span className="flex w-6 items-center justify-center">
            {expanded ? <ChevronUp className="size-4" /> : <ChevronDown className="size-4" />}
          </span>
          <span>
            {expanded
              ? collapseLabel
              : (showMoreLabel?.(hiddenCount) ?? `Show ${hiddenCount} more steps`)}
          </span>
        </button>
      )}

      <div>
        {visibleSteps.map((step, index) => (
          <React.Fragment key={index}>
            {renderStep(step, index, {
              isFirst: index === 0,
              isLast: index === visibleSteps.length - 1,
            })}
          </React.Fragment>
        ))}
      </div>
    </Card>
  );
}

function ChainOfThoughtStep({
  defaultExpanded = false,
  contentVisible,
  ...props
}: ChainOfThoughtStepProps) {
  const [expanded, setExpanded] = React.useState(defaultExpanded);
  return (
    <ChainOfThoughtStepContent
      {...props}
      expanded={expanded}
      onExpandedChange={setExpanded}
      contentVisible={contentVisible ?? expanded}
    />
  );
}

function ControlledChainOfThoughtStep({
  expanded,
  onExpandedChange,
  contentVisible,
  ...props
}: ControlledChainOfThoughtStepProps) {
  return (
    <ChainOfThoughtStepContent
      {...props}
      expanded={expanded}
      onExpandedChange={onExpandedChange}
      contentVisible={contentVisible ?? expanded}
    />
  );
}

interface ChainOfThoughtStepContentProps extends ChainOfThoughtStepBaseProps {
  expanded: boolean;
  onExpandedChange: (expanded: boolean) => void;
  contentVisible: boolean;
}

function ChainOfThoughtStepContent({
  icon,
  label,
  extra,
  onClick,
  children,
  expanded,
  onExpandedChange,
  contentVisible,
  collapsedAdaptiveWidth = false,
  className,
  isFirst,
  isLast,
}: ChainOfThoughtStepContentProps) {
  const hasContent = Boolean(children);
  const clickable = Boolean(onClick || hasContent);
  const shouldFillMaxWidth = !collapsedAdaptiveWidth || contentVisible;

  const handleActivate = () => {
    if (onClick) {
      onClick();
      return;
    }
    if (hasContent) {
      onExpandedChange(!expanded);
    }
  };

  const rowClassName = cn(
    "flex items-center gap-2 px-1 py-2 text-left",
    shouldFillMaxWidth ? "w-full" : "w-fit max-w-full",
    clickable && "cursor-pointer outline-none",
    className,
  );

  const stepClassName = cn(
    "flex gap-2 rounded-md",
    shouldFillMaxWidth ? "w-full" : "w-fit max-w-full",
    clickable && "hover:bg-muted/60 focus-within:ring-ring/50 focus-within:ring-[3px]",
  );

  const iconContent = icon ? (
    <div className="size-3.5">{icon}</div>
  ) : (
    <div className="bg-muted-foreground size-2 rounded-full" />
  );

  const indicator = onClick ? (
    <ChevronRight className="text-muted-foreground size-4" />
  ) : hasContent ? (
    expanded ? (
      <ChevronUp className="text-muted-foreground size-4" />
    ) : (
      <ChevronDown className="text-muted-foreground size-4" />
    )
  ) : null;

  return (
    <div className={stepClassName}>
      <div
        className={cn("flex w-6 shrink-0 flex-col items-center", clickable && "cursor-pointer")}
        onClick={clickable ? handleActivate : undefined}
      >
        <div className={cn("h-2 w-px shrink-0", isFirst === false && "bg-border/80")} />
        <div className="flex h-5 shrink-0 items-center justify-center">{iconContent}</div>
        <div className={cn("w-px flex-1", isLast === false && "bg-border/80")} />
      </div>

      <div className={cn("min-w-0", shouldFillMaxWidth && "flex-1")}>
        {clickable ? (
          <button type="button" className={rowClassName} onClick={handleActivate}>
            <span className={cn("min-w-0", shouldFillMaxWidth && "flex-1")}>{label}</span>
            {extra}
            {indicator}
          </button>
        ) : (
          <div className={rowClassName}>
            <span className={cn("min-w-0", shouldFillMaxWidth && "flex-1")}>{label}</span>
            {extra}
            {indicator}
          </div>
        )}

        {hasContent && !(collapsedAdaptiveWidth && !contentVisible) && (
          <div
            className={cn(
              "grid transition-all duration-200 ease-out",
              shouldFillMaxWidth && "w-full",
            )}
            style={{ gridTemplateRows: contentVisible ? "1fr" : "0fr" }}
          >
            <div className="overflow-hidden">
              <div className="px-1 pb-2 pt-1">{children}</div>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}

export { ChainOfThought, ChainOfThoughtStep, ControlledChainOfThoughtStep };

export type { ChainOfThoughtProps, ChainOfThoughtStepProps, ControlledChainOfThoughtStepProps };
