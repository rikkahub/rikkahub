/**
 * Type definitions for RikkaHub web-ui
 * Aligned with Kotlin types in:
 * - ai/src/main/java/me/rerere/ai/ui/Message.kt
 * - ai/src/main/java/me/rerere/ai/core/MessageRole.kt
 * - ai/src/main/java/me/rerere/ai/core/Usage.kt
 * - app/src/main/java/me/rerere/rikkahub/data/model/Conversation.kt
 */

// ============================================================================
// Core Types
// ============================================================================

/**
 * Message role enum
 * @see ai/src/main/java/me/rerere/ai/core/MessageRole.kt
 */
export type MessageRole = "system" | "user" | "assistant" | "tool";

/**
 * Token usage information
 * @see ai/src/main/java/me/rerere/ai/core/Usage.kt
 */
export interface TokenUsage {
  promptTokens: number;
  completionTokens: number;
  cachedTokens: number;
  totalTokens: number;
}

// ============================================================================
// Message Parts
// ============================================================================

/**
 * Tool approval state
 * @see ai/src/main/java/me/rerere/ai/ui/Message.kt - ToolApprovalState
 */
export type ToolApprovalState =
  | { type: "auto" }
  | { type: "pending" }
  | { type: "approved" }
  | { type: "denied"; reason: string };

/**
 * Base interface for message parts
 */
interface BaseMessagePart {
  metadata?: Record<string, unknown> | null;
}

/**
 * Text message part
 */
export interface TextPart extends BaseMessagePart {
  type: "text";
  text: string;
}

/**
 * Image message part
 */
export interface ImagePart extends BaseMessagePart {
  type: "image";
  url: string;
}

/**
 * Video message part
 */
export interface VideoPart extends BaseMessagePart {
  type: "video";
  url: string;
}

/**
 * Audio message part
 */
export interface AudioPart extends BaseMessagePart {
  type: "audio";
  url: string;
}

/**
 * Document message part
 */
export interface DocumentPart extends BaseMessagePart {
  type: "document";
  url: string;
  fileName: string;
  mime: string;
}

/**
 * Reasoning message part (for thinking/reasoning content)
 */
export interface ReasoningPart extends BaseMessagePart {
  type: "reasoning";
  reasoning: string;
  createdAt?: string;
  finishedAt?: string | null;
}

/**
 * Tool message part (unified tool call and result)
 */
export interface ToolPart extends BaseMessagePart {
  type: "tool";
  toolCallId: string;
  toolName: string;
  input: string;
  output: UIMessagePart[];
  approvalState: ToolApprovalState;
}

/**
 * Union type for all message parts
 * @see ai/src/main/java/me/rerere/ai/ui/Message.kt - UIMessagePart
 */
export type UIMessagePart =
  | TextPart
  | ImagePart
  | VideoPart
  | AudioPart
  | DocumentPart
  | ReasoningPart
  | ToolPart;

// ============================================================================
// Message Annotations
// ============================================================================

/**
 * URL citation annotation
 */
export interface UrlCitationAnnotation {
  type: "url_citation";
  title: string;
  url: string;
}

/**
 * Union type for message annotations
 * @see ai/src/main/java/me/rerere/ai/ui/Message.kt - UIMessageAnnotation
 */
export type UIMessageAnnotation = UrlCitationAnnotation;

// ============================================================================
// Message & Conversation
// ============================================================================

/**
 * UI Message
 * @see ai/src/main/java/me/rerere/ai/ui/Message.kt - UIMessage
 */
export interface UIMessage {
  id: string;
  role: MessageRole;
  parts: UIMessagePart[];
  annotations: UIMessageAnnotation[];
  createdAt: string;
  finishedAt?: string | null;
  modelId?: string | null;
  usage?: TokenUsage | null;
  translation?: string | null;
}

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

// ============================================================================
// API DTOs
// ============================================================================

/**
 * Conversation list item DTO (for list API)
 */
export interface ConversationListDto {
  id: string;
  assistantId: string;
  title: string;
  isPinned: boolean;
  createAt: number;
  updateAt: number;
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

/**
 * Message node DTO (for API response)
 */
export interface MessageNodeDto {
  id: string;
  messages: MessageDto[];
  selectIndex: number;
}

/**
 * Conversation detail DTO (for detail API)
 */
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

// ============================================================================
// Settings Types
// ============================================================================

/**
 * Display settings
 * @see app/src/main/java/me/rerere/rikkahub/data/datastore/PreferencesStore.kt - DisplaySetting
 */
export interface DisplaySetting {
  userNickname: string;
  showUserAvatar: boolean;
  showModelName: boolean;
  showTokenUsage: boolean;
  autoCloseThinking: boolean;
  codeBlockAutoWrap: boolean;
  codeBlockAutoCollapse: boolean;
  showLineNumbers: boolean;
  sendOnEnter: boolean;
  enableAutoScroll: boolean;
  fontSizeRatio: number;
  [key: string]: unknown;
}

/**
 * App settings (streamed via SSE)
 * @see app/src/main/java/me/rerere/rikkahub/data/datastore/PreferencesStore.kt - Settings
 */
export interface Settings {
  dynamicColor: boolean;
  themeId: string;
  developerMode: boolean;
  displaySetting: DisplaySetting;
  enableWebSearch: boolean;
  chatModelId: string;
  assistantId: string;
  providers: unknown[];
  assistants: unknown[];
  [key: string]: unknown;
}

// ============================================================================
// Helper Types
// ============================================================================

/**
 * Extract the current selected message from a MessageNode
 */
export function getCurrentMessage(node: MessageNode): UIMessage {
  return node.messages[node.selectIndex] ?? node.messages[0];
}

/**
 * Extract the current selected message from a MessageNodeDto
 */
export function getCurrentMessageDto(node: MessageNodeDto): MessageDto {
  return node.messages[node.selectIndex] ?? node.messages[0];
}

/**
 * Get all current messages from a conversation
 */
export function getCurrentMessages(conversation: Conversation): UIMessage[] {
  return conversation.messageNodes.map(getCurrentMessage);
}

/**
 * Type guard for TextPart
 */
export function isTextPart(part: UIMessagePart): part is TextPart {
  return part.type === "text";
}

/**
 * Type guard for ImagePart
 */
export function isImagePart(part: UIMessagePart): part is ImagePart {
  return part.type === "image";
}

/**
 * Type guard for ReasoningPart
 */
export function isReasoningPart(part: UIMessagePart): part is ReasoningPart {
  return part.type === "reasoning";
}

/**
 * Type guard for ToolPart
 */
export function isToolPart(part: UIMessagePart): part is ToolPart {
  return part.type === "tool";
}

/**
 * Type guard for DocumentPart
 */
export function isDocumentPart(part: UIMessagePart): part is DocumentPart {
  return part.type === "document";
}
