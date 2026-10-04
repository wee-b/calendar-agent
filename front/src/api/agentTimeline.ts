import type { AgentStep } from './chatStream';

export function timelineNarration(step: AgentStep): string {
    if (step.narration?.trim()) return step.narration;
    if (step.status === 'error') return `${step.label}未完成。`;
    return step.status === 'running' ? `正在${step.label}…` : `${step.label}已完成。`;
}

export function timelineAction(step: AgentStep): string {
    const prefix = step.status === 'running' ? '正在' : step.status === 'error' ? '执行失败：' : '已';
    return `${prefix}${step.label}`;
}

const FRAME_MS = 32;
const MAX_BACKLOG_MS = 6000;

/** 只缓冲实时过程展示；历史记录仍使用服务端保存的完整步骤。 */
export function createAgentStepPresenter(onStep: (step: AgentStep) => void) {
    interface PendingStep {
        step: AgentStep;
        text: string[];
        progress: number;
        shown: number;
    }
    const queue: PendingStep[] = [];
    const displayed = new Map<number, AgentStep>();
    let timer: ReturnType<typeof setTimeout> | undefined;
    let deadline = 0;
    let lastFrameAt = 0;
    let finished = false;
    let stopped = false;
    let resolveDrained!: () => void;
    const drained = new Promise<void>(resolve => { resolveDrained = resolve; });

    const textFor = (step: AgentStep) => step.kind === 'agent'
        ? timelineNarration(step) : step.resultSummary || timelineAction(step);

    const publish = (item: PendingStep, complete: boolean) => {
        const { step } = item;
        const text = item.text.slice(0, item.shown).join('');
        onStep(complete ? { ...step } : {
            ...step,
            status: 'running',
            ...(step.kind === 'agent' ? { narration: text } : {
                resultSummary: step.resultSummary ? text : undefined,
            }),
        });
        if (complete) displayed.set(step.id, { ...step });
    };

    const stop = () => {
        stopped = true;
        if (timer !== undefined) clearTimeout(timer);
        timer = undefined;
        queue.length = 0;
        displayed.clear();
        resolveDrained();
    };

    const schedule = () => {
        if (timer === undefined && !stopped) timer = setTimeout(tick, FRAME_MS);
    };

    const tick = () => {
        timer = undefined;
        const now = performance.now();
        const item = queue[0];
        if (item) {
            if (now >= deadline) {
                // 极长/大量步骤共享一个积压上限，不按步骤数累加等待时间。
                for (const pending of queue) publish(pending, true);
                queue.length = 0;
            } else {
                const remaining = queue.reduce((sum, pending) =>
                    sum + pending.text.length - pending.progress, 0);
                const duration = Math.min(3000, Math.max(900, Math.sqrt(item.text.length) * 180));
                const speed = Math.max(item.text.length / duration,
                    remaining / Math.max(1, deadline - lastFrameAt));
                item.progress = Math.min(item.text.length,
                    item.progress + Math.max(0, now - lastFrameAt) * speed);
                const shown = Math.max(1, Math.floor(item.progress));
                const complete = item.progress >= item.text.length;
                if (shown !== item.shown || complete) {
                    item.shown = shown;
                    publish(item, complete);
                }
                if (complete) queue.shift();
            }
        }
        lastFrameAt = now;
        if (queue.length) schedule();
        else if (finished) stop();
    };

    return {
        append(step: AgentStep) {
            if (stopped || finished) return;
            const now = performance.now();
            const index = queue.findIndex(item => item.step.id === step.id);
            const previous = displayed.get(step.id);
            // 错误立即显示，并移除同 ID 的排队更新，防止旧状态覆盖错误。
            if (step.status === 'error') {
                // 先结清先前步骤，保留时间线顺序；失败后不再播放积压动画。
                for (const pending of queue) {
                    if (pending.step.id === step.id) onStep({ ...step });
                    else publish(pending, true);
                }
                queue.length = 0;
                displayed.set(step.id, { ...step });
                if (index < 0) onStep({ ...step });
                return;
            }
            if (previous?.status === 'error') return;
            const text = Array.from(textFor(step));
            if (index >= 0) {
                const pending = queue[index]!;
                let common = 0;
                while (common < pending.shown && common < text.length
                    && pending.text[common] === text[common]) common++;
                // 服务端重复发送完整快照，保留已经展示的前缀，不重新打字。
                if (pending.text.join('') !== text.join('')) {
                    pending.progress = common;
                    pending.shown = common;
                }
                pending.step = { ...step };
                pending.text = text;
            } else if (previous && textFor(previous) === textFor(step)) {
                displayed.set(step.id, { ...step });
                onStep({ ...step });
                return;
            } else {
                if (!queue.length) {
                    deadline = now + MAX_BACKLOG_MS;
                    lastFrameAt = now;
                }
                queue.push({ step: { ...step }, text, progress: 0, shown: 0 });
            }
            deadline = Math.max(deadline, now + 250);
            schedule();
        },
        finish() {
            if (stopped) return drained;
            finished = true;
            if (!queue.length) stop();
            else schedule();
            return drained;
        },
        cancel: stop,
    };
}
