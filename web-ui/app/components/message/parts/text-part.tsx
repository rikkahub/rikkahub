import Markdown from "~/components/markdown/markdown";

interface TextPartProps {
  text: string;
  isAnimating?: boolean;
}

export function TextPart({ text, isAnimating }: TextPartProps) {
  if (!text) return null;
  return <Markdown content={text} isAnimating={isAnimating} />;
}
