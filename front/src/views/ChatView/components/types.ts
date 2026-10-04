import type { AgentStep } from '../../../api/chatStream';
import type { DocumentReference } from '../../../api/documents';

export interface ChatMessage {
  dialogueId?: number;
  role: string;
  content: string;
  documentReferences?: DocumentReference[];
  loading?: boolean;
  agentSteps?: AgentStep[];
  timelineCollapsed?: boolean;
  runDone?: boolean;
  runStartedAt?: number;
  runFinishedAt?: number;
  responseTimeMs?: number | null;
  dispatchType?: string;
}
