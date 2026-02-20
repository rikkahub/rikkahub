import * as React from "react";

import dayjs from "dayjs";
import { Circle, Search } from "lucide-react";
import { useTranslation } from "react-i18next";

import { Button } from "~/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogTitle,
  DialogTrigger,
} from "~/components/ui/dialog";
import { ScrollArea } from "~/components/ui/scroll-area";
import api from "~/services/api";
import type { MessageSearchResultDto } from "~/types";

export interface ConversationSearchButtonProps {
  onSelect: (id: string) => void;
}

function SnippetText({ snippet }: { snippet: string }) {
  const parts: React.ReactNode[] = [];
  let index = 0;
  let keyIdx = 0;
  while (index < snippet.length) {
    const start = snippet.indexOf("[", index);
    if (start === -1) {
      parts.push(snippet.substring(index));
      break;
    }
    if (start > index) {
      parts.push(snippet.substring(index, start));
    }
    const end = snippet.indexOf("]", start + 1);
    if (end === -1) {
      parts.push(snippet.substring(start));
      break;
    }
    const matched = snippet.substring(start + 1, end);
    parts.push(
      <mark key={keyIdx++} className="bg-transparent font-semibold text-foreground not-italic">
        {matched}
      </mark>,
    );
    index = end + 1;
  }
  return <>{parts}</>;
}

function formatRelativeTime(updateAt: number, t: (key: string) => string): string {
  const date = dayjs(updateAt);
  const today = dayjs().startOf("day");
  const yesterday = today.subtract(1, "day");
  if (date.isSame(today, "day")) return t("conversation_sidebar.today");
  if (date.isSame(yesterday, "day")) return t("conversation_sidebar.yesterday");
  const sameYear = date.year() === today.year();
  const native = date.toDate();
  return new Intl.DateTimeFormat(undefined, {
    month: "short",
    day: "numeric",
    ...(sameYear ? {} : { year: "numeric" }),
  }).format(native);
}

export function ConversationSearchButton({ onSelect }: ConversationSearchButtonProps) {
  const { t } = useTranslation();
  const [open, setOpen] = React.useState(false);
  const [query, setQuery] = React.useState("");
  const [searching, setSearching] = React.useState(false);
  const [error, setError] = React.useState<string | null>(null);
  const [results, setResults] = React.useState<MessageSearchResultDto[]>([]);
  const requestIdRef = React.useRef(0);

  React.useEffect(() => {
    if (!open) {
      setQuery("");
      setResults([]);
      setError(null);
      setSearching(false);
      return;
    }

    const keyword = query.trim();
    if (!keyword) {
      setResults([]);
      setError(null);
      setSearching(false);
      return;
    }

    const requestId = ++requestIdRef.current;
    const timer = window.setTimeout(() => {
      setSearching(true);
      setError(null);

      api
        .get<MessageSearchResultDto[]>("conversations/search", {
          searchParams: { query: keyword },
        })
        .then((data) => {
          if (requestId !== requestIdRef.current) return;
          setResults(data);
        })
        .catch((searchError) => {
          if (requestId !== requestIdRef.current) return;
          if (searchError instanceof Error) {
            setError(searchError.message);
          } else {
            setError(t("conversation_search.search_failed"));
          }
        })
        .finally(() => {
          if (requestId !== requestIdRef.current) return;
          setSearching(false);
        });
    }, 250);

    return () => {
      window.clearTimeout(timer);
    };
  }, [open, query, t]);

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild>
        <Button variant="ghost" size="sm" className="w-full justify-start" type="button">
          <Search className="size-4" />
          {t("conversation_search.search_conversations")}
        </Button>
      </DialogTrigger>
      <DialogContent className="max-h-[80svh] max-w-xl overflow-hidden p-0">
        <DialogTitle className="sr-only">
          {t("conversation_search.search_conversations")}
        </DialogTitle>

        <div className="flex items-center gap-2 border-b px-4 py-3">
          <Search className="size-4 shrink-0 text-muted-foreground" />
          <input
            className="flex-1 bg-transparent text-sm outline-none placeholder:text-muted-foreground"
            value={query}
            onChange={(event) => {
              setQuery(event.target.value);
            }}
            placeholder={t("conversation_search.input_placeholder")}
            autoFocus
          />
        </div>

        <ScrollArea className="h-[420px]">
          <div className="p-2">
            {searching ? (
              <div className="px-2 py-6 text-center text-sm text-muted-foreground">
                {t("conversation_search.searching")}
              </div>
            ) : null}

            {!searching && error ? (
              <div className="px-2 py-6 text-center text-sm text-destructive">{error}</div>
            ) : null}

            {!searching && !error && query.trim().length === 0 ? (
              <div className="px-2 py-6 text-center text-sm text-muted-foreground">
                {t("conversation_search.type_to_start")}
              </div>
            ) : null}

            {!searching && !error && query.trim().length > 0 && results.length === 0 ? (
              <div className="px-2 py-6 text-center text-sm text-muted-foreground">
                {t("conversation_search.no_results")}
              </div>
            ) : null}

            {!searching &&
              !error &&
              results.map((item) => (
                <button
                  key={`${item.conversationId}-${item.messageId}`}
                  type="button"
                  className="flex w-full items-start gap-3 rounded-md px-2 py-2 text-left transition hover:bg-muted"
                  onClick={() => {
                    onSelect(item.conversationId);
                    setOpen(false);
                  }}
                >
                  <div className="min-w-0 flex-1">
                    <div className="flex items-center justify-between gap-2">
                      <span className="truncate text-sm font-medium">
                        {item.title || t("conversation_search.unnamed_conversation")}
                      </span>
                      <span className="shrink-0 text-xs text-muted-foreground">
                        {formatRelativeTime(item.updateAt, t)}
                      </span>
                    </div>
                    <p className="mt-0.5 line-clamp-2 text-xs text-muted-foreground">
                      <SnippetText snippet={item.snippet} />
                    </p>
                  </div>
                </button>
              ))}
          </div>
        </ScrollArea>
      </DialogContent>
    </Dialog>
  );
}
