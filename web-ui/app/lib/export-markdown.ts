import type { ConversationDto, MessageDto } from "~/types";

export function convertMessageToMarkdown(
  message: MessageDto,
  includeReasoning: boolean,
): string {
  const lines: string[] = [];

  for (const part of message.parts) {
    if (part.type === "text" && part.text.trim()) {
      lines.push(part.text.trim());
      lines.push("");
    } else if (part.type === "reasoning" && includeReasoning && part.reasoning.trim()) {
      lines.push("> **Thinking:**");
      for (const line of part.reasoning.trim().split("\n")) {
        lines.push(`> ${line}`);
      }
      lines.push("");
    } else if (part.type === "image" && part.url) {
      lines.push(`![image](${part.url})`);
      lines.push("");
    } else if (part.type === "document" && part.fileName) {
      lines.push(`[${part.fileName}](${part.url})`);
      lines.push("");
    }
  }

  return lines.join("\n").trim();
}

export function convertConversationToMarkdown(
  detail: ConversationDto,
  includeReasoning: boolean,
): string {
  const lines: string[] = [];

  if (detail.title) {
    lines.push(`# ${detail.title}`);
    lines.push("");
  }

  for (const node of detail.messages) {
    const message = node.messages[node.selectIndex] ?? node.messages[0];
    if (!message) continue;

    const roleLabel =
      message.role === "USER"
        ? "## User"
        : message.role === "ASSISTANT"
          ? "## Assistant"
          : `## ${message.role}`;

    lines.push(roleLabel);
    lines.push("");

    for (const part of message.parts) {
      if (part.type === "text" && part.text.trim()) {
        lines.push(part.text.trim());
        lines.push("");
      } else if (part.type === "reasoning" && includeReasoning && part.reasoning.trim()) {
        lines.push("> **Thinking:**");
        for (const line of part.reasoning.trim().split("\n")) {
          lines.push(`> ${line}`);
        }
        lines.push("");
      } else if (part.type === "image" && part.url) {
        lines.push(`![image](${part.url})`);
        lines.push("");
      } else if (part.type === "document" && part.fileName) {
        lines.push(`[${part.fileName}](${part.url})`);
        lines.push("");
      }
    }
  }

  return lines.join("\n").trim();
}

export function downloadMarkdown(content: string, filename: string) {
  const blob = new Blob([content], { type: "text/markdown;charset=utf-8" });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(url);
}
