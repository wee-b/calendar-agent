import assert from 'node:assert/strict';
import { test } from 'node:test';
import { consumeChatStream } from '../src/api/chatStream.ts';

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
        event('tool_call_delta', { receivedChars: 12 }) +
        event('tool_result', { status: 'success' }) +
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
    assert.deepEqual(progress, ['开始：生成回复', '完成：查询日程']);
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
