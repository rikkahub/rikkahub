import * as React from "react";
import { useTranslation } from "react-i18next";

import { Tooltip, TooltipContent, TooltipTrigger } from "~/components/ui/tooltip";
import { cn } from "~/lib/utils";
import { useStickToBottomContext } from "use-stick-to-bottom";

export function getConversationMessageAnchorId(messageId: string): string {
  return `message-anchor-${messageId}`;
}

export interface ConversationQuickJumpItem {
  id: string;
  role: string;
  preview?: string;
}

interface ConversationQuickJumpProps {
  items: ConversationQuickJumpItem[];
}

function getRoleLineClass(role: string): string {
  const normalizedRole = role.toUpperCase();
  if (normalizedRole === "USER") {
    return "bg-primary/35 hover:bg-primary/60";
  }

  if (normalizedRole === "ASSISTANT") {
    return "bg-foreground/25 hover:bg-foreground/50";
  }

  return "bg-muted hover:bg-foreground/40";
}

function getRoleDotClass(role: string): string {
  const normalizedRole = role.toUpperCase();
  if (normalizedRole === "USER") {
    return "bg-primary";
  }

  if (normalizedRole === "ASSISTANT") {
    return "bg-foreground";
  }

  return "bg-foreground/80";
}

function getRoleLabel(
  role: string,
  t: (key: string, options?: Record<string, unknown>) => string,
): string {
  const normalizedRole = role.toUpperCase();
  if (normalizedRole === "USER") return t("quick_jump.role_user");
  if (normalizedRole === "ASSISTANT") return t("quick_jump.role_assistant");
  return t("quick_jump.role_message");
}

export function ConversationQuickJump({ items }: ConversationQuickJumpProps) {
  const { t } = useTranslation();
  const { scrollRef } = useStickToBottomContext();
  const [activeMessageId, setActiveMessageId] = React.useState<string | null>(null);
  const canQuickJump = items.length > 1;
  const activeIndex = React.useMemo(() => {
    if (!activeMessageId) return 0;
    const index = items.findIndex((item) => item.id === activeMessageId);
    return index >= 0 ? index + 1 : 0;
  }, [activeMessageId, items]);

  const resolveActiveMessageId = React.useCallback(() => {
    const scrollElement = scrollRef.current;
    if (!scrollElement || items.length === 0) {
      return items[items.length - 1]?.id ?? null;
    }

    const scrollTopLine = scrollElement.getBoundingClientRect().top + 24;
    let activeId = items[0]?.id ?? null;

    for (const item of items) {
      const anchor = document.getElementById(getConversationMessageAnchorId(item.id));
      if (!anchor) continue;

      if (anchor.getBoundingClientRect().top <= scrollTopLine) {
        activeId = item.id;
      } else {
        break;
      }
    }

    return activeId;
  }, [items, scrollRef]);

  React.useEffect(() => {
    if (!canQuickJump) {
      setActiveMessageId(null);
      return;
    }

    const scrollElement = scrollRef.current;
    if (!scrollElement) {
      setActiveMessageId(resolveActiveMessageId());
      return;
    }

    let frameId: number | null = null;
    const updateActive = () => {
      frameId = null;
      const nextActiveId = resolveActiveMessageId();
      setActiveMessageId((prev) => (prev === nextActiveId ? prev : nextActiveId));
    };
    const scheduleUpdate = () => {
      if (frameId !== null) return;
      frameId = window.requestAnimationFrame(updateActive);
    };

    updateActive();
    scrollElement.addEventListener("scroll", scheduleUpdate, { passive: true });
    window.addEventListener("resize", scheduleUpdate);

    return () => {
      scrollElement.removeEventListener("scroll", scheduleUpdate);
      window.removeEventListener("resize", scheduleUpdate);
      if (frameId !== null) {
        window.cancelAnimationFrame(frameId);
      }
    };
  }, [canQuickJump, resolveActiveMessageId, scrollRef]);

  const handleQuickJump = React.useCallback((messageId: string) => {
    const anchor = document.getElementById(getConversationMessageAnchorId(messageId));
    if (!anchor) return;

    setActiveMessageId(messageId);
    anchor.scrollIntoView({ behavior: "smooth", block: "start" });
  }, []);

  if (!canQuickJump) {
    return null;
  }

  return (
    <div className="pointer-events-none absolute inset-y-0 left-1/2 z-20 hidden w-full max-w-3xl -translate-x-1/2 lg:block">
      <div className="pointer-events-auto absolute top-1/2 -right-5 -translate-y-1/2">
        <div className="flex flex-col items-start gap-1">
          {items.map((item, index) => {
            const isActive = activeMessageId === item.id;
            const roleLabel = getRoleLabel(item.role, t);

            return (
              <Tooltip key={`quick-jump-${item.id}`}>
                <TooltipTrigger asChild>
                  <button
                    type="button"
                    className="flex w-8 items-center justify-start gap-1 transition-colors"
                    aria-label={t("quick_jump.jump_to_message", {
                      index: index + 1,
                      role: roleLabel,
                    })}
                    title={t("quick_jump.message_title", { index: index + 1, role: roleLabel })}
                    onClick={() => {
                      handleQuickJump(item.id);
                    }}
                  >
                    <span
                      className={cn(
                        "h-1.5 w-5 rounded-full transition-colors",
                        getRoleLineClass(item.role),
                        isActive && "bg-foreground/80",
                      )}
                    />
                    <span
                      className={cn(
                        "size-1.5 rounded-full transition-opacity duration-200",
                        getRoleDotClass(item.role),
                        isActive ? "animate-pulse opacity-100" : "opacity-0",
                      )}
                    />
                  </button>
                </TooltipTrigger>
                <TooltipContent side="left" sideOffset={8} className="max-w-64 text-left">
                  <div className="space-y-0.5">
                    <div className="text-[11px] text-background/75">
                      {index + 1}/{items.length} · {roleLabel}
                    </div>
                    <div>{item.preview?.trim() || t("quick_jump.no_preview")}</div>
                  </div>
                </TooltipContent>
              </Tooltip>
            );
          })}
          <div className="mt-1 w-5 text-center text-[10px] text-muted-foreground/80 tabular-nums">
            {activeIndex}/{items.length}
          </div>
        </div>
      </div>
    </div>
  );
}
