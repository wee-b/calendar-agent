// 展示时长随字数次线性增长：短回复保留阅读节奏，长回复自动加速。
const MIN_DURATION_MS = 900;
const MAX_DURATION_MS = 6000;
const LATE_CHUNK_DURATION_MS = 250;
const FRAME_MS = 32;

export function createStreamTextPresenter(onText: (text: string) => void) {
    let characters: string[] = [];
    let shown = 0;
    let startedAt: number | undefined;
    let lastFrameAt = 0;
    let deadline = 0;
    let credit = 0;
    let finished = false;
    let stopped = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    let resolveDrained!: () => void;
    const drained = new Promise<void>(resolve => { resolveDrained = resolve; });

    const stop = () => {
        stopped = true;
        if (timer !== undefined) clearTimeout(timer);
        timer = undefined;
        characters = [];
        resolveDrained();
    };

    const schedule = () => {
        if (timer === undefined && !stopped) timer = setTimeout(tick, FRAME_MS);
    };

    const tick = () => {
        timer = undefined;
        const now = performance.now();
        const remaining = characters.length - shown;
        // 按真实经过时间推进，刷新率变化或后台定时器节流不会延长积压时间。
        credit += Math.max(0, remaining - credit) * Math.max(0, now - lastFrameAt)
            / Math.max(1, deadline - lastFrameAt);
        const count = now >= deadline ? remaining : Math.min(remaining, Math.floor(credit));
        lastFrameAt = now;
        if (count > 0) {
            onText(characters.slice(shown, shown + count).join(''));
            shown += count;
            credit = Math.max(0, credit - count);
        }
        if (shown === characters.length) {
            credit = 0;
            if (finished && now >= deadline) {
                stop();
                return;
            }
            if (!finished) return;
        }
        schedule();
    };

    return {
        append(text: string) {
            if (stopped || finished || !text) return;
            const now = performance.now();
            const wasEmpty = shown === characters.length;
            // 按 Unicode 码点切分，避免把 emoji 的代理对拆成两个展示帧。
            for (const character of text) characters.push(character);
            if (startedAt === undefined) startedAt = now;
            const duration = Math.min(MAX_DURATION_MS,
                Math.max(MIN_DURATION_MS, Math.sqrt(characters.length) * 180));
            // 网络本身较慢时只平滑新到的尾部，不再从头等待完整展示时长。
            deadline = Math.max(startedAt + duration, now + LATE_CHUNK_DURATION_MS);
            if (wasEmpty) lastFrameAt = now;
            if (shown === 0) {
                onText(characters[0]!);
                shown = 1;
            }
            schedule();
        },
        finish() {
            if (stopped) return drained;
            finished = true;
            if (startedAt === undefined) stop();
            else schedule();
            return drained;
        },
        cancel: stop,
    };
}
