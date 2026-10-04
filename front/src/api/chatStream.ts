/** Python 聊天 SSE 协议；只有 done 表示成功，EOF 或 error 都不能当作完成。 */
export interface AgentStep {
    id: number;
    kind: 'agent' | 'tool' | 'summary';
    label: string;
    narration?: string | null;
    resultSummary?: string | null;
    status: 'running' | 'success' | 'error';
    elapsedMs: number;
    round?: number | null;
}

export interface ChatStreamHandlers {
    onToken: (token: string) => void;
    onDone: () => void;
    onProgress?: (message: string) => void;
    onResponseTime?: (milliseconds: number) => void;
    onDispatchType?: (dispatchType: string) => void;
    onAgentStep?: (step: AgentStep) => void;
}

export async function consumeChatStream(
    stream: ReadableStream<Uint8Array>, handlers: ChatStreamHandlers,
): Promise<void> {
    const reader = stream.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    let completed = false;
    let receivedResult = false;
    const toolLabels: Record<string, string> = {
        queryDayDetail: '查询日程', queryTodoList: '查询待办', queryMonthCount: '查询月历',
        createTodo: '创建待办', batchCreateTodos: '同步规划', updateTodo: '修改待办',
        deleteTodo: '删除待办', toggleTodoDate: '更新完成状态', saveDailyNote: '保存日记',
        removeTodoDay: '移除待办日期', addTodoDay: '添加待办日期',
    };
    const agentLabels: Record<string, string> = {
        route: '识别意图', chat: '生成回复', planner: '制定规划', executor: '处理操作', image: '生成图片',
    };
    const calls = new Map<string, string>();

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
            case 'agent_step':
                if (!Number.isInteger(payload.id) || typeof payload.label !== 'string'
                    || !['running', 'success', 'error'].includes(payload.status)) {
                    throw new Error('过程事件格式错误');
                }
                handlers.onAgentStep?.(payload as AgentStep);
                break;
            case 'assistant_delta':
                if (typeof payload.delta !== 'string') throw new Error('回复数据格式错误');
                handlers.onToken(payload.delta);
                break;
            case 'agent_status':
                handlers.onProgress?.('开始：' + (payload.stage === 'tool'
                    ? (payload.agent === 'chat' ? '查询日程' : '处理日历操作')
                    : agentLabels[payload.agent] || '生成回复'));
                break;
            case 'tool_call_start':
                calls.set(payload.callId, toolLabels[payload.tool] || '处理日历操作');
                handlers.onProgress?.('开始：' + calls.get(payload.callId));
                break;
            case 'tool_result':
                handlers.onProgress?.((payload.status === 'success' ? '完成：' : '失败：')
                    + (calls.get(payload.callId) || '处理日历操作'));
                break;
            case 'result':
                receivedResult = true;
                if (typeof payload.responseTimeMs === 'number') handlers.onResponseTime?.(payload.responseTimeMs);
                if (typeof payload.dispatchType === 'string') handlers.onDispatchType?.(payload.dispatchType);
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
