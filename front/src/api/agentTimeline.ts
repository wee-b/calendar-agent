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
