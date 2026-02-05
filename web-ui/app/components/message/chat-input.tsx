import * as React from "react";

import { Send, Paperclip, Mic, Image, Sparkles } from "lucide-react";
import { Button } from "~/components/ui/button";
import { Textarea } from "~/components/ui/textarea";
import { cn } from "~/lib/utils";

export interface ChatInputProps {
  className?: string;
}

export function ChatInput({ className }: ChatInputProps) {
  return (
    <div
      className={cn(
        "bg-background/95 backdrop-blur supports-[backdrop-filter]:bg-background/60",
        className,
      )}
    >
      <div className="mx-auto w-full max-w-3xl px-4 py-4">
        <div className="relative flex flex-col gap-2 rounded-2xl border bg-muted/50 p-2 shadow-sm transition-shadow focus-within:shadow-md focus-within:ring-1 focus-within:ring-ring">
          <Textarea
            placeholder="输入消息..."
            className="min-h-[60px] max-h-[200px] resize-none border-0 bg-transparent p-2 text-sm shadow-none focus-visible:ring-0"
            rows={2}
          />
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-1">
              <Button
                variant="ghost"
                size="icon"
                className="size-8 rounded-full text-muted-foreground hover:text-foreground"
              >
                <Paperclip className="size-4" />
              </Button>
              <Button
                variant="ghost"
                size="icon"
                className="size-8 rounded-full text-muted-foreground hover:text-foreground"
              >
                <Image className="size-4" />
              </Button>
              <Button
                variant="ghost"
                size="icon"
                className="size-8 rounded-full text-muted-foreground hover:text-foreground"
              >
                <Mic className="size-4" />
              </Button>
              <div className="mx-2 h-4 w-px bg-border" />
              <Button
                variant="ghost"
                size="sm"
                className="h-8 gap-1.5 rounded-full px-3 text-xs text-muted-foreground hover:text-foreground"
              >
                <Sparkles className="size-3.5" />
                <span>增强</span>
              </Button>
            </div>
            <Button
              size="icon"
              className="size-9 rounded-full bg-primary text-primary-foreground shadow-sm hover:bg-primary/90"
            >
              <Send className="size-4" />
            </Button>
          </div>
        </div>
        <p className="mt-2 text-center text-xs text-muted-foreground">
          按 Enter 发送，Shift + Enter 换行
        </p>
      </div>
    </div>
  );
}
