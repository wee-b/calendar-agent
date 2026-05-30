// src/api/chat.ts
import request from '../utils/request';

export interface ChatRequestDTO {
    sessionId: string;
    message: string;
}

export interface ChatResponseVO {
    sessionId: string;
    aiResult: string;
    aiAudioUrl?: string;
    intent?: string;
    executeResult?: string;
}

export interface ChatHistoryItemVO {
    dialogueId: number;
    role: string;
    content: string;
    createTime: string;
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

// 2. 发送对话消息
export const sendChatAPI = (data: ChatRequestDTO): Promise<ChatResponseVO> => {
    return request.post('/chat', data);
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
