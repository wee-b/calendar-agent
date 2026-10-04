import assert from 'node:assert/strict';
import { test } from 'node:test';
import { consumeChatStream } from '../src/api/chatStream.ts';
import { timelineNarration, createAgentStepPresenter } from '../src/api/agentTimeline.ts';
import { createStreamTextPresenter } from '../src/utils/streamText.ts';

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

const clock = (t) => {
    let now = 0;
    t.mock.timers.enable({ apis: ['setTimeout'] });
    t.mock.method(performance, 'now', () => now);
    return (milliseconds) => {
        for (let remaining = milliseconds; remaining > 0;) {
            const step = Math.min(16, remaining);
            now += step;
            t.mock.timers.tick(step);
            remaining -= step;
        }
    };
};

test('short replies start immediately but finish progressively after the minimum duration', async (t) => {
    const advance = clock(t);
    let visible = '';
    let done = false;
    const presenter = createStreamTextPresenter(text => { visible += text; });
    presenter.append('这是一个简短的回复。');
    const finished = presenter.finish().then(() => { done = true; });
    assert.equal(visible, '这');
    advance(450);
    assert.ok(visible.length > 1 && visible.length < 10);
    assert.equal(done, false);
    advance(430);
    assert.ok(visible.length < 10);
    advance(64);
    await finished;
    assert.equal(visible, '这是一个简短的回复。');
    assert.equal(done, true);
});

test('long replies accelerate and drain within six seconds plus one frame', async (t) => {
    const advance = clock(t);
    let visible = '';
    const reply = '长回复🙂'.repeat(5000);
    const presenter = createStreamTextPresenter(text => { visible += text; });
    presenter.append(reply);
    const finished = presenter.finish();
    advance(3000);
    assert.ok(visible.length > reply.length * 0.4 && visible.length < reply.length * 0.6);
    advance(3040);
    await finished;
    assert.equal(visible, reply);
});

test('new chunks preserve order and an expanding backlog speeds up', async (t) => {
    const advance = clock(t);
    let visible = '';
    const presenter = createStreamTextPresenter(text => { visible += text; });
    presenter.append('先到达的内容。');
    advance(320);
    const partial = visible;
    const tail = '后续内容。'.repeat(1000);
    presenter.append(tail);
    const finished = presenter.finish();
    advance(320);
    assert.ok(visible.startsWith(partial));
    assert.ok(visible.length > 100);
    advance(5408);
    await finished;
    assert.equal(visible, '先到达的内容。' + tail);
});

test('slow upstream chunks only add a short tail after the initial display window', async (t) => {
    const advance = clock(t);
    let visible = '';
    const presenter = createStreamTextPresenter(text => { visible += text; });
    presenter.append('开始');
    advance(10000);
    assert.equal(visible, '开始');
    presenter.append('迟到的文字🙂');
    const finished = presenter.finish();
    advance(128);
    assert.notEqual(visible, '开始迟到的文字🙂');
    advance(128);
    await finished;
    assert.equal(visible, '开始迟到的文字🙂');
});

test('empty and single-character replies finish, without splitting emoji', async (t) => {
    const advance = clock(t);
    const chunks = [];
    const empty = createStreamTextPresenter(text => chunks.push(text));
    empty.append('');
    await empty.finish();
    assert.deepEqual(chunks, []);
    const presenter = createStreamTextPresenter(text => chunks.push(text));
    presenter.append('🙂');
    const finished = presenter.finish();
    assert.deepEqual(chunks, ['🙂']);
    advance(928);
    await finished;
});

test('cancelling a failed stream discards buffered text and prevents later updates', async (t) => {
    const advance = clock(t);
    let visible = '';
    const presenter = createStreamTextPresenter(text => { visible += text; });
    presenter.append('不应该在错误后继续展示的内容');
    const finished = presenter.finish();
    presenter.cancel();
    visible = '连接失败';
    presenter.append('更多文字');
    advance(10000);
    await finished;
    assert.equal(visible, '连接失败');
});

const setup = (t) => {
    const advance = clock(t);
    const steps = new Map();
    const updates = [];
    const presenter = createAgentStepPresenter(step => {
        steps.set(step.id, step);
        updates.push(step);
    });
    return { presenter, advance, steps, updates };
};

const agent = (id, narration = '我会先查看今天的安排。') => ({
    id, kind: 'agent', label: '识别意图', narration, status: 'success', elapsedMs: 10,
});

test('short loop narration appears progressively and subsequent steps wait their turn', async (t) => {
    const { presenter, advance, steps } = setup(t);
    const first = agent(1);
    const second = agent(2, '接下来制定计划。');
    presenter.append(first);
    presenter.append(second);
    const finished = presenter.finish();
    advance(32);
    assert.equal(steps.get(1).narration, '我');
    assert.equal(steps.get(1).status, 'running');
    assert.equal(steps.has(2), false);
    advance(400);
    assert.ok(steps.get(1).narration.length > 1);
    assert.ok(steps.get(1).narration.length < first.narration.length);
    advance(448);
    assert.equal(steps.get(1).status, 'running');
    advance(1200);
    await finished;
    assert.deepEqual([...steps.values()], [first, second]);
});

test('long narration and many loop steps share a bounded backlog', async (t) => {
    const { presenter, advance, steps } = setup(t);
    const originals = Array.from({ length: 40 }, (_, i) => agent(i + 1, '较长的过程说明🙂'.repeat(30)));
    for (const step of originals) presenter.append(step);
    const finished = presenter.finish();
    advance(3000);
    assert.ok(steps.size > 1 && steps.size < 40);
    advance(3040);
    await finished;
    assert.deepEqual([...steps.values()], originals);
});

test('same-ID snapshots merge without duplicate rows or restarting unchanged narration', async (t) => {
    const { presenter, advance, steps } = setup(t);
    const step = agent(1);
    presenter.append({ ...step, status: 'running' });
    advance(450);
    const prefix = steps.get(1).narration;
    for (let i = 0; i < 20; i++) presenter.append(step);
    advance(64);
    assert.ok(steps.get(1).narration.startsWith(prefix));
    assert.equal(steps.size, 1);
    const finished = presenter.finish();
    advance(450);
    await finished;
    assert.deepEqual(steps.get(1), step);
});

test('model narration replaces placeholder text and tool summaries are also paced', async (t) => {
    const { presenter, advance, steps } = setup(t);
    const step = agent(1);
    presenter.append({ ...step, narration: undefined, status: 'running' });
    advance(320);
    presenter.append(step);
    const tool = { id: 2, kind: 'tool', label: '查询日程', status: 'success',
        resultSummary: '今天有三项待办🙂。', elapsedMs: 100 };
    presenter.append(tool);
    advance(1056);
    assert.deepEqual(steps.get(1), step);
    assert.ok(steps.get(2).resultSummary.length < tool.resultSummary.length);
    assert.equal(steps.get(2).status, 'running');
    const finished = presenter.finish();
    advance(2000);
    await finished;
    assert.deepEqual(steps.get(2), tool);
    assert.equal(timelineNarration(steps.get(1)), step.narration);
});

test('new steps after a slow network gap still get a readable display window', async (t) => {
    const { presenter, advance, steps } = setup(t);
    presenter.append(agent(1));
    advance(10000);
    const second = agent(2);
    presenter.append(second);
    const finished = presenter.finish();
    advance(450);
    assert.equal(steps.get(2).status, 'running');
    advance(510);
    await finished;
    assert.deepEqual(steps.get(2), second);
});

test('errors display immediately in order and cannot be overwritten by queued snapshots', async (t) => {
    const { presenter, advance, steps } = setup(t);
    presenter.append(agent(1));
    presenter.append(agent(2));
    const failure = { ...agent(2), status: 'error' };
    presenter.append(failure);
    assert.deepEqual([...steps.keys()], [1, 2]);
    assert.deepEqual(steps.get(2), failure);
    presenter.append(agent(2));
    const finished = presenter.finish();
    advance(10000);
    await finished;
    assert.deepEqual(steps.get(2), failure);
});

test('empty timelines finish immediately and cancellation stops all later UI updates', async (t) => {
    const { presenter, advance, updates } = setup(t);
    await createAgentStepPresenter(() => assert.fail('empty timeline emitted a step')).finish();
    presenter.append(agent(1));
    advance(64);
    const finished = presenter.finish();
    presenter.cancel();
    const count = updates.length;
    advance(10000);
    await finished;
    assert.equal(updates.length, count);
});
