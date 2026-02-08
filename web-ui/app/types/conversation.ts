import type { UIMessage } from "./message";

/**
 * Message node - container for message branching
 * @see app/src/main/java/me/rerere/rikkahub/data/model/Conversation.kt - MessageNode
 */
export interface MessageNode {
  id: string;
  messages: UIMessage[];
  selectIndex: number;
}

/**
 * Conversation
 * @see app/src/main/java/me/rerere/rikkahub/data/model/Conversation.kt - Conversation
 */
export interface Conversation {
  id: string;
  assistantId: string;
  title: string;
  messageNodes: MessageNode[];
  truncateIndex: number;
  chatSuggestions: string[];
  isPinned: boolean;
  createAt: number;
  updateAt: number;
}
