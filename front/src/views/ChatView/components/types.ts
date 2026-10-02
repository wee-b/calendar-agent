export interface ChatMessage {
  dialogueId?: number;
  role: string;
  content: string;
  pendingContent?: string;
  loading?: boolean;
  thinking?: string[];
  thinkingCollapsed?: boolean;
  thinkingDone?: boolean;
  thinkingStartedAt?: number;
  thinkingFinishedAt?: number;
  responseTimeMs?: number | null;
  showServerResponseTime?: boolean;
  dispatchType?: string;
}
