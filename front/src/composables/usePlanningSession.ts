import { ref } from 'vue';
import { parsePlanningState } from '../api/planningState.ts';
import type { ChatSessionState, PlanningState } from '../api/planningState.ts';

/** 会话切换及流式结果使旧读取失效，避免跨会话或旧快照覆盖最新规划状态。 */
export function usePlanningSession(load: (sessionId: string) => Promise<ChatSessionState>) {
    const state = ref<PlanningState | null>(null);
    const loading = ref(false);
    const error = ref(false);
    let activeSessionId: string | null = null;
    let revision = 0;

    function reset(sessionId: string | null = null) {
        activeSessionId = sessionId;
        revision++;
        state.value = null;
        loading.value = false;
        error.value = false;
    }

    function apply(sessionId: string, value: unknown): boolean {
        if (activeSessionId !== sessionId) return false;
        const parsed = parsePlanningState(value);
        revision++;
        state.value = parsed;
        loading.value = false;
        error.value = parsed === null;
        return parsed !== null;
    }

    async function refresh(sessionId: string) {
        if (activeSessionId !== sessionId) return;
        const requestRevision = ++revision;
        loading.value = true;
        error.value = false;
        try {
            const result = await load(sessionId);
            if (requestRevision !== revision || activeSessionId !== sessionId) return;
            if (result.sessionId !== sessionId || !parsePlanningState(result.planning)) throw new Error('规划状态无效');
            apply(sessionId, result.planning);
        } catch {
            if (requestRevision !== revision || activeSessionId !== sessionId) return;
            state.value = null;
            error.value = true;
        } finally {
            if (activeSessionId === sessionId && requestRevision === revision) loading.value = false;
        }
    }

    return { state, loading, error, reset, apply, refresh };
}
