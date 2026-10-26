<template>
  <section v-if="planning?.status === 'awaiting_answers'" class="planning-panel" aria-label="补充规划条件" :aria-busy="busy">
    <button type="button" class="planning-heading" :aria-expanded="expanded" @click="expanded = !expanded">
      <span><strong>{{ busy ? '正在继续规划' : '补充规划条件' }}</strong><small>{{ planning.questions.length }} 个问题</small></span>
      <span class="planning-chevron" :class="{ open: expanded }" aria-hidden="true">⌄</span>
    </button>
    <div class="planning-meta">
      <span>{{ rangeLabel }}</span>
      <span v-if="planning.referenceCount">沿用 {{ planning.referenceCount }} 篇引用文章</span>
    </div>
    <form v-if="expanded" class="planning-form" @submit.prevent="submit">
      <p class="planning-hint">逐项回答后继续规划，也可以直接在下方输入框回复。</p>
      <fieldset :disabled="disabled">
        <label v-for="(question, index) in planning.questions" :key="`${planning.questionSetId}-${index}`" class="planning-question">
          <span><b>{{ index + 1 }}</b>{{ question }}</span>
          <textarea v-model="answers[index]" rows="2" maxlength="2000" required placeholder="填写你的回答" />
        </label>
        <div class="planning-actions">
          <button type="submit" class="primary" :disabled="!answerMessage">提交回答，继续规划</button>
          <button type="button" @click="$emit('send', '取消当前规划')">取消本次规划</button>
        </div>
      </fieldset>
    </form>
    <p v-else class="planning-collapsed">{{ busy ? '正在结合你的回答生成并校验方案…' : '展开填写回答，或在输入框中补充条件。' }}</p>
  </section>
  <section v-else-if="planning?.status === 'draft_ready'" class="planning-panel ready" aria-label="规划草稿操作">
    <div class="planning-heading"><strong>规划草稿已就绪</strong></div>
    <div class="planning-meta"><span>{{ rangeLabel }}</span><span>可以继续修改，或选择下一步</span></div>
    <div class="planning-actions">
      <button type="button" class="primary" :disabled="!canAct" @click="$emit('send', '同步到日历')">同步到日历</button>
      <button type="button" :disabled="!canAct" @click="$emit('send', '生成一张规划示意图')">生成示意图</button>
    </div>
  </section>
  <div v-else-if="loadError" class="planning-state-error" role="status">
    <span>暂时无法读取规划状态</span>
    <button type="button" :disabled="disabled" @click="$emit('refresh')">重新读取</button>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { buildPlanningAnswers, canUsePlanActions } from '../../../api/planningState';
import type { PlanningState } from '../../../api/planningState';

const props = defineProps<{
  planning: PlanningState | null;
  busy: boolean;
  loading: boolean;
  loadError: boolean;
  loggedIn: boolean;
}>();
const emit = defineEmits<{ (e: 'send', content: string): void; (e: 'refresh'): void }>();
const answers = ref<string[]>([]);
const expanded = ref(true);
let questionSetId: string | null = null;
const disabled = computed(() => props.busy || props.loading || !props.loggedIn);
const canAct = computed(() => canUsePlanActions(props.planning, disabled.value || props.loadError));
const answerMessage = computed(() => props.planning?.status === 'awaiting_answers'
  ? buildPlanningAnswers(props.planning.questions, answers.value) : '');
const rangeLabel = computed(() => {
  const period = props.planning?.dateRange;
  if (!period) return props.planning?.status === 'awaiting_answers' ? '规划日期待确认' : '以草稿中的日期为准';
  return period.startDate === period.endDate ? period.startDate : `${period.startDate} 至 ${period.endDate}`;
});

watch(() => props.planning, planning => {
  // 读取失败时保留已填回答；服务端确认新一组问题后再重置。
  if (!planning) return;
  if (planning.status !== 'awaiting_answers') {
    answers.value = [];
    questionSetId = null;
  } else if (planning.questionSetId !== questionSetId) {
    questionSetId = planning.questionSetId;
    answers.value = planning.questions.map(() => '');
    expanded.value = true;
  }
}, { immediate: true });
watch(() => props.busy, busy => { if (busy) expanded.value = false; });

const submit = () => {
  if (!disabled.value && answerMessage.value) emit('send', answerMessage.value);
};
</script>

<style scoped>
.planning-panel { flex-shrink: 0; margin: 0 0 12px; padding: 13px 16px; border: 1px solid #e4e4e7; border-radius: 16px; background: #fff; color: #27272a; box-shadow: 0 4px 16px #18181b04; }
.planning-heading { display: flex; justify-content: space-between; align-items: center; gap: 12px; width: 100%; padding: 0; border: 0; background: none; color: inherit; text-align: left; }
button.planning-heading { cursor: pointer; }
.planning-heading strong { font-size: 14px; font-weight: 650; }
.planning-heading small { margin-left: 10px; color: #71717a; font-size: 12px; }
.planning-chevron { color: #71717a; transform: rotate(-90deg); transition: transform .15s; }
.planning-chevron.open { transform: rotate(0); }
.planning-meta { display: flex; flex-wrap: wrap; gap: 4px 12px; margin-top: 6px; color: #71717a; font-size: 12px; line-height: 1.5; }
.planning-form { max-height: min(38vh, 360px); overflow-y: auto; margin-top: 12px; scrollbar-width: thin; }
.planning-hint, .planning-collapsed { margin: 8px 0 0; color: #71717a; font-size: 12px; line-height: 1.5; }
.planning-form fieldset { min-width: 0; padding: 0; margin: 12px 0 0; border: 0; }
.planning-question { display: flex; flex-direction: column; gap: 7px; margin-bottom: 12px; font-size: 13px; line-height: 1.5; }
.planning-question > span { display: flex; align-items: baseline; gap: 8px; }
.planning-question b { display: inline-grid; place-items: center; flex-shrink: 0; width: 20px; height: 20px; border-radius: 6px; background: #f4f4f5; color: #52525b; font-size: 12px; }
.planning-question textarea { width: 100%; min-height: 56px; padding: 9px 11px; box-sizing: border-box; border: 1px solid #e4e4e7; border-radius: 10px; background: #fafafa; color: #27272a; font: inherit; resize: vertical; }
.planning-question textarea:focus { outline: 2px solid #d4d4d8; outline-offset: 1px; background: #fff; }
.planning-actions { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 12px; }
.planning-actions button, .planning-state-error button { min-height: 34px; padding: 6px 12px; border: 1px solid #e4e4e7; border-radius: 9px; background: #fff; color: #52525b; font-size: 12px; cursor: pointer; }
.planning-actions button.primary { background: #27272a; color: #fff; border-color: #27272a; }
.planning-actions button:hover:not(:disabled) { border-color: #71717a; }
.planning-actions button:disabled, .planning-question textarea:disabled, .planning-state-error button:disabled { opacity: .5; cursor: not-allowed; }
.planning-state-error { display: flex; align-items: center; justify-content: space-between; gap: 8px; flex-shrink: 0; margin-bottom: 10px; color: #71717a; font-size: 12px; }
@media (max-width: 600px) { .planning-panel { padding: 11px 12px; } .planning-form { max-height: 32vh; } }
</style>
