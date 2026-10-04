import assert from 'node:assert/strict';
import { test } from 'node:test';
import { consumeChatStream } from '../src/api/chatStream.ts';
import { timelineNarration } from '../src/api/agentTimeline.ts';

const event = (name, data) => `event: ${name}\r\ndata: ${JSON.stringify(data)}\r\n\r\n`;
const stream = (body, chunkSize = 3) => {
    const bytes = new TextEncoder().encode(body);
    return new ReadableStream({
        start(controller) {
            for (let i = 0; i < bytes.length; i += chunkSize) controller.enqueue(bytes.slice(i, i + chunkSize));
            controller.close();
        },
    });
};

test('Python SSE handles fragmented UTF-8 and emits only reply text', async () => {
    let reply = '';
    let done = 0;
    let time = 0;
    const progress = [];
    await consumeChatStream(stream(
        event('ping', {}) + event('agent_status', { stage: 'model' }) +
        event('assistant_delta', { delta: '你好' }) +
        event('tool_call_start', { callId: 'query-1', tool: 'queryDayDetail' }) +
        event('tool_call_delta', { receivedChars: 12 }) +
        event('tool_result', { callId: 'query-1', status: 'success' }) +
        event('assistant_delta', { delta: '，日程已查询。' }) +
        event('result', { aiResult: '你好，日程已查询。', responseTimeMs: 123 }) +
        event('done', { rounds: 2, responseTimeMs: 123 }),
    ), {
        onToken: text => reply += text,
        onDone: () => done++,
        onResponseTime: milliseconds => time = milliseconds,
        onProgress: message => progress.push(message),
    });
    assert.equal(reply, '你好，日程已查询。');
    assert.equal(done, 1);
    assert.equal(time, 123);
    assert.deepEqual(progress, ['开始：生成回复', '开始：查询日程', '完成：查询日程']);
});

test('assistant text reaches the UI before the SSE stream completes', async () => {
    let visible = '';
    let done = false;
    const response = new ReadableStream({
        start(controller) {
            controller.enqueue(new TextEncoder().encode(event('assistant_delta', { delta: '先' })));
            setTimeout(() => {
                controller.enqueue(new TextEncoder().encode(
                    event('assistant_delta', { delta: '显示' }) + event('result', {}) + event('done', {}),
                ));
                controller.close();
            }, 30);
        },
    });
    const pending = consumeChatStream(response, {
        onToken: chunk => { visible += chunk; },
        onDone: () => { done = true; },
    });
    await new Promise(resolve => setTimeout(resolve, 10));
    assert.equal(visible, '先');
    assert.equal(done, false);
    await pending;
    assert.equal(visible, '先显示');
    assert.equal(done, true);
});

test('error and truncated streams never report success', async () => {
    for (const body of [
        event('assistant_delta', { delta: '半句' }),
        event('error', { message: '模型超时' }),
        event('done', {}),
    ]) {
        let done = false;
        await assert.rejects(consumeChatStream(stream(body), {
            onToken() {}, onDone() { done = true; },
        }));
        assert.equal(done, false);
    }
});

test('done without final newline is accepted after saved result', async () => {
    let done = false;
    await consumeChatStream(stream(event('result', {}) + event('done', {}).trimEnd()), {
        onToken() {}, onDone() { done = true; },
    });
    assert.equal(done, true);
});

test('agent milestones update by stable ID without exposing tool arguments', async () => {
    const steps = [];
    await consumeChatStream(stream(
        event('agent_step', { id: 1, kind: 'tool', label: '查询日程', status: 'running', elapsedMs: 40 }) +
        event('agent_step', { id: 1, kind: 'tool', label: '查询日程', status: 'success', elapsedMs: 40,
            resultSummary: '2026-10-04：2 项待办。' }) +
        event('result', { responseTimeMs: 100 }) + event('done', { rounds: 1 }),
    ), { onToken() {}, onDone() {}, onAgentStep: step => steps.push(step) });
    assert.deepEqual(steps.map(step => step.status), ['running', 'success']);
    assert.equal(steps[0].label, '查询日程');
    assert.equal(steps[1].resultSummary, '2026-10-04：2 项待办。');
});

test('timeline prefers persisted model-authored public narration', () => {
    const step = { id: 1, kind: 'agent', label: '识别意图', status: 'success', elapsedMs: 8,
        narration: '我会把三天分成基础、实作和复盘。' };
    assert.equal(timelineNarration(step), step.narration);
    assert.equal(timelineNarration({ ...step, narration: null }), '识别意图已完成。');
});
