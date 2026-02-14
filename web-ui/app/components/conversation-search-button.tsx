import * as React from "react";

import { Pin, Search } from "lucide-react";
import { useTranslation } from "react-i18next";

import { Button } from "~/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "~/components/ui/dialog";
import { Input } from "~/components/ui/input";
import { ScrollArea } from "~/components/ui/scroll-area";
import api from "~/services/api";
import type { ConversationListDto, PagedResult } from "~/types";

const SEARCH_PAGE_SIZE = 50;

export interface ConversationSearchButtonProps {
  onSelect: (id: string) => void;
}

export function ConversationSearchButton({ onSelect }: ConversationSearchButtonProps) {
  const { t } = useTranslation();
  const [open, setOpen] = React.useState(false);
  const [query, setQuery] = React.useState("");
  const [searching, setSearching] = React.useState(false);
  const [error, setError] = React.useState<string | null>(null);
  const [results, setResults] = React.useState<ConversationListDto[]>([]);
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
        .get<PagedResult<ConversationListDto>>("conversations/paged", {
          searchParams: {
            offset: 0,
            limit: SEARCH_PAGE_SIZE,
            query: keyword,
          },
        })
        .then((data) => {
          if (requestId !== requestIdRef.current) return;
          setResults(data.items);
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
        <DialogHeader className="border-b px-6 py-4">
          <DialogTitle>{t("conversation_search.search_conversations")}</DialogTitle>
        </DialogHeader>

        <div className="space-y-3 px-6 py-4">
          <Input
            value={query}
            onChange={(event) => {
              setQuery(event.target.value);
            }}
            placeholder={t("conversation_search.input_placeholder")}
            autoFocus
          />

          <ScrollArea className="h-[360px] rounded-md border">
            <div className="space-y-1 p-2">
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
                    key={item.id}
                    type="button"
                    className="flex w-full items-center rounded-md px-2 py-2 text-left text-sm transition hover:bg-muted"
                    onClick={() => {
                      onSelect(item.id);
                      setOpen(false);
                    }}
                  >
                    <span className="min-w-0 flex-1 truncate">
                      {item.title || t("conversation_search.unnamed_conversation")}
                    </span>
                    {item.isPinned ? <Pin className="size-3 text-primary" /> : null}
                  </button>
                ))}
            </div>
          </ScrollArea>
        </div>
      </DialogContent>
    </Dialog>
  );
}
