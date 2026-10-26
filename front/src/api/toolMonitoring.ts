import request from '../utils/request';

const AI_BASE_URL = (import.meta.env.VITE_AI_API_BASE_URL || '').replace(/\/$/, '');

export interface ToolMetrics {
  count: number;
  success: number;
  timeout: number;
  error: number;
  cancelled: number;
  success_rate: number | null;
  avg_ms: number | null;
  p95_ms: number | null;
}

export interface NamedToolMetrics extends ToolMetrics {
  tool_name: string;
}

export interface ToolStats {
  count: number;
  running: number;
  uncertain: number;
  completed: ToolMetrics;
  tools: NamedToolMetrics[];
  per_turn: {
    turns_with_calls: number;
    avg_calls: number | null;
    max_calls: number | null;
    logical_count: number;
  };
}

export interface ToolCallItem {
  id: number;
  attempt_id: string;
  logical_call_id: string;
  turn_id: string;
  agent: string;
  tool_name: string;
  read_only: boolean;
  attempt_no: number;
  status: string;
  outcome: string;
  timeout_ms: number;
  elapsed_ms: number | null;
  error_code: string | null;
  started_at: string;
  finished_at: string | null;
}

export interface ToolCallPage {
  items: ToolCallItem[];
  has_more: boolean;
  next_before_id: number | null;
}

export const getToolStatsAPI = (params: { start: string; end: string; sessionId?: string }): Promise<ToolStats> =>
  request.get('/chat/tool-stats', { baseURL: AI_BASE_URL, params });

export const getToolCallsAPI = (params: { sessionId: string; limit?: number; beforeId?: number }): Promise<ToolCallPage> =>
  request.get('/chat/tool-calls', { baseURL: AI_BASE_URL, params });
