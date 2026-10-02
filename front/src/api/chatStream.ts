/** Python 聊天 SSE 协议；只有 done 表示成功，EOF 或 error 都不能当作完成。 */
export interface ChatStreamHandlers {
    onToken: (token: string) => void;
    onDone: () => void;
    onProgress?: (message: string) => void;
    onResponseTime?: (milliseconds: number) => void;
}

export async function consumeChatStream(
    stream: ReadableStream<Uint8Array>, handlers: ChatStreamHandlers,
): Promise<void> {
    const reader = stream.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    let completed = false;
    let receivedResult = false;

    const dispatch = (frame: string) => {
        if (!frame.trim() || frame.trimStart().startsWith(':')) return;
        let event = 'message';
        const data: string[] = [];
        for (const line of frame.split(/\r?\n/)) {
            if (line.startsWith('event:')) event = line.slice(6).trim();
            if (line.startsWith('data:')) data.push(line.slice(5).trimStart());
        }
        if (!data.length || event === 'ping') return;
        const payload = JSON.parse(data.join('\n'));
        switch (event) {
            case 'assistant_delta':
                if (typeof payload.delta !== 'string') throw new Error('回复数据格式错误');
                handlers.onToken(payload.delta);
                break;
            case 'agent_status':
                handlers.onProgress?.(payload.stage === 'tool' ? '开始：查询日程' : '开始：生成回复');
                break;
            case 'tool_call_start':
                handlers.onProgress?.('开始：准备日程查询');
                break;
            case 'tool_result':
                handlers.onProgress?.(payload.status === 'success' ? '完成：查询日程' : '失败：查询日程');
                break;
            case 'result':
                receivedResult = true;
                if (typeof payload.responseTimeMs === 'number') handlers.onResponseTime?.(payload.responseTimeMs);
                break;
            case 'done':
                if (!receivedResult) throw new Error('回复未保存，请重试');
                completed = true;
                handlers.onDone();
                break;
            case 'error':
                throw new Error(payload.message || '对话处理失败');
        }
    };

    try {
        while (!completed) {
            const { done, value } = await reader.read();
            buffer += done ? decoder.decode() : decoder.decode(value, { stream: true });
            const frames = buffer.split(/\r?\n\r?\n/);
            buffer = frames.pop() || '';
            for (const frame of frames) {
                dispatch(frame);
                if (completed) break;
            }
            if (done) {
                if (!completed && buffer.trim()) dispatch(buffer);
                if (!completed) throw new Error('回复连接提前结束，请重试');
                break;
            }
        }
    } finally {
        await reader.cancel().catch(() => undefined);
        reader.releaseLock();
    }
}
