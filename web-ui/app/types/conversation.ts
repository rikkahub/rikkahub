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
 * 会话开始后固定在会话上的配置；会话开始前没有这份配置，聊天输入区的切换直接改助手
 * @see app/src/main/java/me/rerere/rikkahub/data/model/ConversationConfig.kt - ConversationConfig
 */
export interface ConversationConfig {
  chatModelId?: string | null;
  reasoningLevel?: string | null;
  enableWebSearch?: boolean;
  /** 模型内置搜索，覆盖模型自身的开关 */
  builtInSearch?: boolean;
  mcpServers?: string[];
  workspaceId?: string | null;
  enabledSkills?: string[];
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
  chatSuggestions: string[];
  isPinned: boolean;
  customSystemPrompt?: string | null;
  modeInjectionIds?: string[];
  lorebookIds?: string[];
  config?: ConversationConfig | null;
  /** Absolute path inside the workspace rootfs */
  workspaceCwd?: string | null;
  /** 所属文件夹（助手内分组），null 表示未归入任何文件夹 */
  folderId?: string | null;
  createAt: number;
  updateAt: number;
}
