/**
 * Display settings
 * @see app/src/main/java/me/rerere/rikkahub/data/datastore/PreferencesStore.kt - DisplaySetting
 */
export interface DisplaySetting {
  userNickname: string;
  userAvatar?: AssistantAvatar;
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

export interface AssistantTag {
  id: string;
  name: string;
}

export interface AssistantAvatar {
  type?: string;
  content?: string;
  url?: string;
  [key: string]: unknown;
}

export interface AssistantProfile {
  id: string;
  chatModelId?: string | null;
  thinkingBudget?: number | null;
  name: string;
  avatar?: AssistantAvatar;
  tags: string[];
  [key: string]: unknown;
}

export type ModelType = "CHAT" | "IMAGE" | "EMBEDDING";
export type ModelModality = "TEXT" | "IMAGE";
export type ModelAbility = "TOOL" | "REASONING";

export interface BuiltInTool {
  type?: string;
  [key: string]: unknown;
}

export interface ProviderModel {
  id: string;
  modelId: string;
  displayName: string;
  type: ModelType;
  inputModalities?: ModelModality[];
  outputModalities?: ModelModality[];
  abilities?: ModelAbility[];
  tools?: BuiltInTool[];
  [key: string]: unknown;
}

export interface ProviderProfile {
  id: string;
  enabled: boolean;
  name: string;
  models: ProviderModel[];
  [key: string]: unknown;
}

export interface SearchServiceOption {
  id: string;
  type?: string;
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
  providers: ProviderProfile[];
  assistants: AssistantProfile[];
  assistantTags: AssistantTag[];
  searchServices: SearchServiceOption[];
  searchServiceSelected: number;
  [key: string]: unknown;
}
