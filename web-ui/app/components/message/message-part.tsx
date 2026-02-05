import type { UIMessagePart } from "~/types";
import { TextPart } from "./parts/text-part";
import { ReasoningPart } from "./parts/reasoning-part";
import { ImagePart } from "./parts/image-part";

interface MessagePartProps {
  part: UIMessagePart;
}

export function MessagePart({ part }: MessagePartProps) {
  switch (part.type) {
    case "text":
      return <TextPart text={part.text} />;
    case "image":
      return <ImagePart url={part.url} />;
    case "reasoning":
      return (
        <ReasoningPart
          reasoning={part.reasoning}
          isFinished={part.finishedAt != null}
        />
      );
    default:
      return (
        <div className="text-xs text-muted-foreground">
          [Unsupported: {part.type}]
        </div>
      );
  }
}
