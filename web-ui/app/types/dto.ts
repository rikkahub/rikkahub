import type { UIMessagePart } from "./parts";

export interface ConversationListDto {
  id: string;
  assistantId: string;
  title: string;
  isPinned: boolean;
  createAt: number;
  updateAt: number;
}

export interface PagedResult<T> {
  items: T[];
  nextOffset?: number | null;
  hasMore: boolean;
}

export interface ConversationListInvalidateEventDto {
  type: "invalidate";
  assistantId: string;
  timestamp: number;
}

/**
 * Message DTO (for API response)
 * @see app/src/main/java/me/rerere/rikkahub/web/dto/WebDto.kt - MessageDto
 */
export interface MessageDto {
  id: string;
  role: string;
  parts: UIMessagePart[];
  createdAt: string;
  finishedAt?: string | null;
  translation?: string | null;
}

export interface MessageNodeDto {
  id: string;
  messages: MessageDto[];
  selectIndex: number;
}

export interface ConversationDto {
  id: string;
  assistantId: string;
  title: string;
  messages: MessageNodeDto[];
  truncateIndex: number;
  chatSuggestions: string[];
  isPinned: boolean;
  createAt: number;
  updateAt: number;
  isGenerating: boolean;
}
