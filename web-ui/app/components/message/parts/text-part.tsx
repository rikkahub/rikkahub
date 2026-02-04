import Markdown from "~/components/markdown";

interface TextPartProps {
  text: string;
}

export function TextPart({ text }: TextPartProps) {
  if (!text) return null;
  return <Markdown content={text} />;
}
