export interface ChatMessage {
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
}
