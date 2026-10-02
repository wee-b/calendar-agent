// src/api/chat.ts
import request from '../utils/request';
import { getToken, clearAuth } from '../utils/auth';
import { consumeChatStream } from './chatStream';

// 独立于 Java 的 API 地址；开发环境留空，通过 Vite /chat 代理访问 Python。
const AI_BASE_URL = (import.meta.env.VITE_AI_API_BASE_URL || '').replace(/\/$/, '');

export interface ChatRequestDTO {
    sessionId: string;
    message: string;
}

export interface ChatResponseVO {
    sessionId: string;
    aiResult: string;
    aiAudioUrl?: string;
    responseTimeMs?: number;
    intent?: string;
    executeResult?: string;
    needDispatchAgent?: boolean;
    dispatchType?: string;
    currentAgent?: string;
    nextAgent?: string;
    flowStage?: string;
}

export interface ChatHistoryItemVO {
    dialogueId: number;
    role: string;
    content: string;
    createTime: string;
    responseTimeMs?: number | null;
}

export interface ChatSessionVO {
    sessionId: string;
    title: string;
    createTime: string;
    lastMessageTime?: string | null;
    messageCount: number;
}

export interface ChatHistoryPage {
    items: ChatHistoryItemVO[];
    hasMore: boolean;
    nextBeforeId: number | null;
}

// 1. 开启新对话
export const newSessionAPI = (): Promise<Record<string, string>> => {
    return request.post('/chat/new-session', undefined, { baseURL: AI_BASE_URL });
};

// 2. 发送对话消息（非流式，保留兼容）
export const sendChatAPI = (data: ChatRequestDTO): Promise<ChatResponseVO> => {
    return request.post('/chat', data, { baseURL: AI_BASE_URL });
};

// 2b. 流式发送对话消息（SSE）
export const streamChatAPI = async (
    data: ChatRequestDTO,
    onToken: (token: string) => void,
    onDone: () => void,
    onError: (error: string) => void,
    onOpen?: () => void,
    onProgress?: (message: string) => void,
    onResponseTime?: (responseTimeMs: number) => void,
    _onDispatchType?: (dispatchType: string) => void
): Promise<void> => {
    const token = getToken();
    const tokenName = import.meta.env.VITE_TOKEN_KEY || 'yvli-token';

    try {
        const response = await fetch(`${AI_BASE_URL}/chat/stream`, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                ...(token ? { [tokenName]: token } : {})
            },
            body: JSON.stringify(data)
        });

        if (!response.ok) {
            if (response.status === 401) {
                clearAuth();
                onError('登录状态已过期，请重新登录');
                return;
            }
            const text = await response.text();
            onError(text || `请求失败 (${response.status})`);
            return;
        }

        onOpen?.();

        if (!response.body) throw new Error('未收到回复数据流');
        await consumeChatStream(response.body, { onToken, onDone, onProgress, onResponseTime });
    } catch (error: any) {
        onError(error.message || '网络连接异常');
    }
};

// 3. 获取历史会话列表
export const getSessionsAPI = (): Promise<ChatSessionVO[]> => {
    return request.get('/chat/sessions', { baseURL: AI_BASE_URL });
};

// 4. 获取某次会话的具体聊天记录
export const getHistoryAPI = (
    sessionId: string, beforeId?: number, limit = 20,
): Promise<ChatHistoryPage> => {
    return request.get('/chat/history', { baseURL: AI_BASE_URL, params: { sessionId, beforeId, limit } });
};

// 5. 删除整个对话
export const deleteSessionAPI = (sessionId: string): Promise<void> => {
    return request.delete('/chat/session', { baseURL: AI_BASE_URL, params: { sessionId } });
};

// 6. 撤回上一轮对话
export const deleteLastRoundAPI = (sessionId: string): Promise<void> => {
    return request.delete('/chat/last-round', { baseURL: AI_BASE_URL, params: { sessionId } });
};
