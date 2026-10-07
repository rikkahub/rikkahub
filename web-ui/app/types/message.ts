import type { MessageRole, TokenUsage } from "./core";
import type { UIMessageAnnotation } from "./annotations";
import type { UIMessagePart } from "./parts";

/**
 * 消息上保存的模型名称快照，模型被删除后用它显示来源
 * @see ai/src/main/java/me/rerere/ai/ui/Message.kt - ModelSnapshot
 */
export interface ModelSnapshot {
  modelId: string;
  displayName: string;
}

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
  modelSnapshot?: ModelSnapshot | null;
  usage?: TokenUsage | null;
  translation?: string | null;
  isContextCheckpoint?: boolean;
}
