// src/api/chat.ts
import request from '../utils/request';
import { getToken, clearAuth } from '../utils/auth';

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
    messageCount: number;
}

// 1. 开启新对话
export const newSessionAPI = (): Promise<Record<string, string>> => {
    return request.post('/chat/new-session');
};

// 2. 发送对话消息（非流式，保留兼容）
export const sendChatAPI = (data: ChatRequestDTO): Promise<ChatResponseVO> => {
    return request.post('/chat', data);
};

// 2b. 流式发送对话消息（SSE）
export const streamChatAPI = async (
    data: ChatRequestDTO,
    onToken: (token: string) => void,
    onDone: () => void,
    onError: (error: string) => void,
    onOpen?: () => void,
    onProgress?: (message: string) => void,
    onResponseTime?: (responseTimeMs: number) => void
): Promise<void> => {
    const baseUrl = import.meta.env.VITE_API_BASE_URL || '';
    const token = getToken();
    const tokenName = import.meta.env.VITE_TOKEN_KEY || 'yvli-token';

    try {
        const response = await fetch(`${baseUrl}/chat/stream`, {
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

        const reader = response.body!.getReader();
        const decoder = new TextDecoder();
        let buffer = '';

        while (true) {
            const { done, value } = await reader.read();
            if (done) break;

            buffer += decoder.decode(value, { stream: true });
            const parts = buffer.split('\n\n');
            buffer = parts.pop() || '';

            for (const part of parts) {
                const lines = part.split('\n');
                let eventName = 'message';
                const dataLines: string[] = [];
                for (const line of lines) {
                    if (line.startsWith('event:')) eventName = line.substring(6).trim();
                    if (line.startsWith('data:')) dataLines.push(line.substring(5).trim());
                }

                const content = dataLines.join('\n');
                if (!content) continue;

                if (eventName === 'progress') {
                    onProgress?.(content);
                } else if (eventName === 'responseTime') {
                    const responseTimeMs = Number(content);
                    if (Number.isFinite(responseTimeMs)) onResponseTime?.(responseTimeMs);
                } else {
                    onToken(content);
                }
            }
        }

        onDone();
    } catch (error: any) {
        onError(error.message || '网络连接异常');
    }
};

// 3. 获取历史会话列表
export const getSessionsAPI = (): Promise<ChatSessionVO[]> => {
    return request.get('/chat/sessions');
};

// 4. 获取某次会话的具体聊天记录
export const getHistoryAPI = (sessionId: string): Promise<ChatHistoryItemVO[]> => {
    return request.get('/chat/history', { params: { sessionId } });
};

// 5. 删除整个对话
export const deleteSessionAPI = (sessionId: string): Promise<void> => {
    return request.delete('/chat/session', { params: { sessionId } });
};

// 6. 撤回上一轮对话
export const deleteLastRoundAPI = (sessionId: string): Promise<void> => {
    return request.delete('/chat/last-round', { params: { sessionId } });
};
