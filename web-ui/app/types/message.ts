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
  /** 模型实际生成耗时（毫秒），不含首 token 延迟、工具执行和审批等待 */
  generationTimeMs?: number | null;
  /** 最后一次请求自己的 usage；usage 是多步工具调用的累计值 */
  lastUsage?: TokenUsage | null;
  /** 这条消息累计请求了多少次模型 */
  stepCount?: number | null;
  translation?: string | null;
  isContextCheckpoint?: boolean;
}
