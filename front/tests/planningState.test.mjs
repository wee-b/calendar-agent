import assert from 'node:assert/strict';
import { test } from 'node:test';
import { buildPlanningAnswers, canUsePlanActions, parsePlanningState } from '../src/api/planningState.ts';
import { usePlanningSession } from '../src/composables/usePlanningSession.ts';

const awaiting = (id = '1234567890abcdef', questions = ['从哪天开始、到哪天结束？', '每天可以投入多久？']) => ({
    status: 'awaiting_answers', questions, questionSetId: id, dateRange: null, referenceCount: 2,
});
const ready = () => ({ status: 'draft_ready', questions: [], dateRange: { startDate: '2026-10-26', endDate: '2026-11-01' } });
const snapshot = (sessionId, planning) => ({ sessionId, flowStage: 'PLAN', planning });
const deferred = () => {
    let resolve, reject;
    const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
    return { promise, resolve, reject };
};

test('planning metadata recognizes pending answers and preserves the confirmed date range', () => {
    assert.deepEqual(parsePlanningState(awaiting()), awaiting());
    assert.equal(parsePlanningState(ready()).dateRange.endDate, '2026-11-01');
    assert.equal(parsePlanningState({ status: 'none' }).status, 'none');
});

test('invalid or contradictory planning metadata cannot enable draft actions', () => {
    for (const value of [null, [], {}, { status: 'unknown' }, awaiting('invalid'), awaiting(undefined, []),
        awaiting(undefined, ['一？', '二？', '三？', '四？']), awaiting(undefined, ['重复？', '重复？']),
        { ...ready(), questions: ['仍有待回答问题？'] },
        { ...ready(), dateRange: { startDate: '2026-02-30', endDate: '2026-03-02' } },
        { ...ready(), dateRange: { startDate: '2026-11-01', endDate: '2026-10-26' } },
        { ...awaiting(), referenceCount: 6 },
    ]) {
        assert.equal(parsePlanningState(value), null);
        assert.equal(canUsePlanActions(parsePlanningState(value), false), false);
    }
    assert.equal(canUsePlanActions(parsePlanningState(awaiting()), false), false);
    assert.equal(canUsePlanActions(parsePlanningState(ready()), true), false);
    assert.equal(canUsePlanActions(parsePlanningState(ready()), false), true);
});

test('clarification answers carry their questions and require a complete nonblank bounded answer set', () => {
    const questions = awaiting().questions;
    const message = buildPlanningAnswers(questions, [' 10月26日至11月1日 ', '每天两小时\n周末休息']);
    assert.match(message, /补充规划条件/);
    assert.match(message, /1\. 从哪天开始、到哪天结束？\n回答：10月26日至11月1日/);
    assert.match(message, /每天两小时\n周末休息/);
    for (const answers of [[], ['只有一个回答'], [' ', '两小时'], ['时间', '字'.repeat(2001)]]) {
        assert.equal(buildPlanningAnswers(questions, answers), '');
    }
});

test('switching sessions ignores earlier state responses', async () => {
    const a = deferred(), b = deferred();
    const planning = usePlanningSession(id => id === 'a' ? a.promise : b.promise);
    planning.reset('a');
    const first = planning.refresh('a');
    planning.reset('b');
    const second = planning.refresh('b');
    b.resolve(snapshot('b', awaiting('bbbbbbbbbbbbbbbb')));
    await second;
    a.resolve(snapshot('a', ready()));
    await first;
    assert.equal(planning.state.value.questionSetId, 'bbbbbbbbbbbbbbbb');
    assert.equal(planning.loading.value, false);
    assert.equal(planning.error.value, false);
});

test('a committed SSE result invalidates earlier snapshot requests', async () => {
    const request = deferred();
    const planning = usePlanningSession(() => request.promise);
    planning.reset('s');
    const pending = planning.refresh('s');
    planning.apply('s', ready());
    request.resolve(snapshot('s', awaiting()));
    await pending;
    assert.equal(planning.state.value.status, 'draft_ready');
    assert.equal(planning.loading.value, false);
    assert.equal(planning.apply('another-session', awaiting()), false);
    assert.equal(planning.state.value.status, 'draft_ready');
});

test('failed state recovery hides actions and a retry restores the same question set', async () => {
    let attempts = 0;
    const planning = usePlanningSession(async id => {
        if (++attempts === 1) throw new Error('offline');
        return snapshot(id, awaiting());
    });
    planning.reset('s');
    await planning.refresh('s');
    assert.equal(planning.state.value, null);
    assert.equal(planning.error.value, true);
    assert.equal(planning.loading.value, false);
    await planning.refresh('s');
    assert.equal(planning.state.value.questionSetId, awaiting().questionSetId);
    assert.equal(planning.error.value, false);
});

test('a reset or logout prevents stale requests from restoring private questions', async () => {
    const request = deferred();
    const planning = usePlanningSession(() => request.promise);
    planning.reset('s');
    const pending = planning.refresh('s');
    planning.reset();
    request.resolve(snapshot('s', awaiting()));
    await pending;
    assert.equal(planning.state.value, null);
    assert.equal(planning.loading.value, false);
    assert.equal(planning.apply('s', ready()), false);
});

test('wrong-session or malformed state payloads fail closed', async () => {
    for (const result of [snapshot('other-session', ready()), snapshot('s', { ...ready(), questions: ['待回答'] })]) {
        const planning = usePlanningSession(async () => result);
        planning.reset('s');
        await planning.refresh('s');
        assert.equal(planning.state.value, null);
        assert.equal(planning.error.value, true);
    }
});
