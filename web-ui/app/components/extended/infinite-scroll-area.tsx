import * as React from "react";
import InfiniteScroll from "react-infinite-scroll-component";
import { useTranslation } from "react-i18next";

import { cn } from "~/lib/utils";

export interface InfiniteScrollAreaProps extends Omit<React.ComponentProps<"div">, "id"> {
  /** Total count of currently loaded items (used by InfiniteScroll internally) */
  dataLength: number;
  /** Callback to load the next page */
  next: () => void;
  /** Whether more items are available */
  hasMore: boolean;
  /** Element shown while loading more items */
  loader?: React.ReactNode;
  /** Stable unique id for the scrollable container */
  scrollTargetId?: string;
}

function InfiniteScrollArea({
  className,
  children,
  dataLength,
  next,
  hasMore,
  loader,
  scrollTargetId = "infinite-scroll-target",
  ...props
}: InfiniteScrollAreaProps) {
  const { t } = useTranslation();
  const containerRef = React.useRef<HTMLDivElement>(null);
  const defaultLoader = (
    <div className="px-2 py-2 text-center text-xs text-muted-foreground">
      {t("infinite_scroll.load_more")}
    </div>
  );
  const effectiveLoader = loader ?? defaultLoader;

  // When the container isn't scrollable (content shorter than viewport)
  // but there's still more data, auto-trigger the next load.
  React.useEffect(() => {
    const el = containerRef.current;
    if (!el || !hasMore) return;

    // Wait a frame so the DOM has been updated with the latest children.
    const rafId = requestAnimationFrame(() => {
      if (el.scrollHeight <= el.clientHeight) {
        next();
      }
    });

    return () => cancelAnimationFrame(rafId);
  }, [dataLength, hasMore, next]);

  return (
    <div
      ref={containerRef}
      data-slot="infinite-scroll-area"
      id={scrollTargetId}
      className={cn("styled-scrollbar min-h-0 flex-1 overflow-y-auto", className)}
      {...props}
    >
      <InfiniteScroll
        dataLength={dataLength}
        next={next}
        hasMore={hasMore}
        loader={effectiveLoader}
        scrollableTarget={scrollTargetId}
      >
        {children}
      </InfiniteScroll>
    </div>
  );
}

export { InfiniteScrollArea };
