/** 服务端返回的规划交互摘要；不依赖回复文案推断当前状态。 */
export interface PlanningState {
    status: 'none' | 'awaiting_answers' | 'draft_ready';
    questions: string[];
    questionSetId: string | null;
    dateRange: { startDate: string; endDate: string } | null;
    referenceCount: number;
}

export interface ChatSessionState {
    sessionId: string;
    flowStage: string;
    planning: PlanningState;
}

const isDate = (value: unknown): value is string => {
    if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
    const day = new Date(value + 'T00:00:00Z');
    return Number.isFinite(day.getTime()) && day.toISOString().slice(0, 10) === value;
};

export function parsePlanningState(value: unknown): PlanningState | null {
    if (!value || typeof value !== 'object' || Array.isArray(value)) return null;
    const data = value as Record<string, unknown>;
    if (!['none', 'awaiting_answers', 'draft_ready'].includes(String(data.status))) return null;
    const questions = data.questions ?? [];
    if (!Array.isArray(questions) || questions.length > 3
        || questions.some(q => typeof q !== 'string' || !q.trim() || q.length > 240)) return null;
    if (data.status === 'awaiting_answers') {
        if (!questions.length || new Set(questions).size !== questions.length
            || typeof data.questionSetId !== 'string' || !/^[a-f0-9]{16}$/.test(data.questionSetId)) return null;
    } else if (questions.length) return null;
    const referenceCount = data.referenceCount ?? 0;
    if (!Number.isInteger(referenceCount) || Number(referenceCount) < 0 || Number(referenceCount) > 5) return null;
    let dateRange: PlanningState['dateRange'] = null;
    if (data.dateRange != null) {
        if (typeof data.dateRange !== 'object') return null;
        const range = data.dateRange as Record<string, unknown>;
        if (!isDate(range.startDate) || !isDate(range.endDate) || range.endDate < range.startDate) return null;
        dateRange = { startDate: range.startDate, endDate: range.endDate };
    }
    return { status: data.status as PlanningState['status'], questions: questions.map(q => q.trim()),
        questionSetId: data.status === 'awaiting_answers' ? data.questionSetId as string : null,
        dateRange, referenceCount: Number(referenceCount) };
}

export function buildPlanningAnswers(questions: readonly string[], answers: readonly string[]): string {
    if (!questions.length || questions.length > 3 || questions.length !== answers.length
        || questions.some(q => !q.trim()) || answers.some(answer => !answer.trim() || answer.length > 2000)) return '';
    return '补充规划条件：\n\n' + questions.map((question, index) =>
        `${index + 1}. ${question.trim()}\n回答：${answers[index]!.trim()}`).join('\n\n');
}

export function canUsePlanActions(state: PlanningState | null, busy: boolean): boolean {
    return !busy && state?.status === 'draft_ready';
}
