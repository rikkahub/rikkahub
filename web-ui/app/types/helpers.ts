import type { Conversation, MessageNode } from "./conversation";
import type { MessageDto, MessageNodeDto } from "./dto";
import type { UIMessage } from "./message";
import type {
  DocumentPart,
  ImagePart,
  ReasoningPart,
  TextPart,
  ToolPart,
  UIMessagePart,
} from "./parts";

export function getCurrentMessage(node: MessageNode): UIMessage {
  return node.messages[node.selectIndex] ?? node.messages[0];
}

export function getCurrentMessageDto(node: MessageNodeDto): MessageDto {
  return node.messages[node.selectIndex] ?? node.messages[0];
}

export function getCurrentMessages(conversation: Conversation): UIMessage[] {
  return conversation.messageNodes.map(getCurrentMessage);
}

/** 运行时通知单独开一轮时的消息：只有 notice，不是用户说的话 */
export function isNoticeOnly(message: Pick<UIMessage, "parts">): boolean {
  return message.parts.length > 0 && message.parts.every((part) => part.type === "notice");
}

export function isTextPart(part: UIMessagePart): part is TextPart {
  return part.type === "text";
}

export function isImagePart(part: UIMessagePart): part is ImagePart {
  return part.type === "image";
}

export function isReasoningPart(part: UIMessagePart): part is ReasoningPart {
  return part.type === "reasoning";
}

export function isToolPart(part: UIMessagePart): part is ToolPart {
  return part.type === "tool";
}

export function isDocumentPart(part: UIMessagePart): part is DocumentPart {
  return part.type === "document";
}
