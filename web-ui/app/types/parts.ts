/**
 * Tool approval state
 * @see ai/src/main/java/me/rerere/ai/ui/Message.kt - ToolApprovalState
 */
export type ToolApprovalState =
  | { type: "auto" }
  | { type: "pending" }
  | { type: "approved" }
  | { type: "denied"; reason: string };

interface BaseMessagePart {
  metadata?: Record<string, unknown> | null;
}

export interface TextPart extends BaseMessagePart {
  type: "text";
  text: string;
}

export interface ImagePart extends BaseMessagePart {
  type: "image";
  url: string;
}

export interface VideoPart extends BaseMessagePart {
  type: "video";
  url: string;
}

export interface AudioPart extends BaseMessagePart {
  type: "audio";
  url: string;
}

export interface DocumentPart extends BaseMessagePart {
  type: "document";
  url: string;
  fileName: string;
  mime: string;
}

export interface ReasoningPart extends BaseMessagePart {
  type: "reasoning";
  reasoning: string;
  createdAt?: string;
  finishedAt?: string | null;
}

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
