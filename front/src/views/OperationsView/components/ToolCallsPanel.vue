<template>
  <section class="tool-monitoring-panel">
    <div class="operations-inner">
      <header class="page-heading">
        <div>
          <span class="eyebrow">运行概览 <span class="account-tag">当前账号</span></span>
          <h1>工具调用监控</h1>
          <p>了解工具的调用表现，追踪执行异常与待核实写入。</p>
        </div>
        <button class="refresh-button" :disabled="!isLoggedIn || refreshing" @click="refreshAll">
          <AppIcon name="refresh" :class="{ spinning: refreshing }" />
          {{ refreshing ? '刷新中' : '刷新数据' }}
        </button>
      </header>

      <div v-if="!isLoggedIn" class="state-panel login-state">
        <AppIcon name="monitor" />
        <h3>登录后查看运维数据</h3>
        <p>统计和调用记录仅对当前账号可见。</p>
        <button class="primary-button" @click="showAuthModal = true">登录账号</button>
      </div>

      <template v-else>
        <div class="filter-bar">
          <div class="time-presets" role="group" aria-label="统计时间范围">
            <button v-for="period in periods" :key="period.hours" :aria-pressed="hours === period.hours"
              :class="{ selected: hours === period.hours }" @click="hours = period.hours">{{ period.label }}</button>
          </div>
          <div class="session-filter">
            <label for="monitor-session">会话范围</label>
            <ElSelect id="monitor-session" v-model="sessionId" filterable clearable allow-create
              default-first-option :value-on-clear="''" :loading="sessionsLoading" placeholder="全部会话 / 输入会话 ID" aria-label="会话范围">
              <ElOption v-for="session in sessions" :key="session.sessionId" :value="session.sessionId"
                :label="session.title || session.sessionId" />
            </ElSelect>
          </div>
        </div>
        <p v-if="sessionsError" class="filter-hint">会话列表读取失败，可直接输入会话 ID。<button class="text-button" @click="loadSessions">重试</button></p>

        <div class="snapshot-line" aria-live="polite">
          <span>{{ sessionId ? '所选会话' : '全部会话' }} · {{ periodLabel }}</span>
          <span>{{ statsLoading ? '正在读取统计…' : updatedAt ? `更新于 ${formatClock(updatedAt)}` : '尚未更新' }}</span>
        </div>

        <div v-if="statsError" class="error-panel" role="alert">
          <div><strong>统计数据加载失败</strong><p>请检查服务连接后重试。</p></div>
          <button class="outline-button" @click="loadStats">重新加载</button>
        </div>

        <div v-else class="metric-grid" :aria-busy="statsLoading">
          <article v-for="card in metricCards" :key="card.label" class="metric-card" :class="{ attention: card.attention }">
            <span class="metric-label">{{ card.label }}</span>
            <div class="metric-value" :class="{ 'is-loading': statsLoading }">{{ card.value }}<small v-if="card.unit">{{ card.unit }}</small></div>
            <span class="metric-caption">{{ card.caption }}</span>
          </article>
        </div>

        <template v-if="stats && !statsLoading">
          <section class="panel execution-panel" aria-label="执行结果概览">
            <div class="execution-results">
              <div class="section-heading compact"><h3>执行结果</h3><span>{{ formatCount(stats.completed.count) }} 次已结束 · {{ formatCount(stats.running) }} 次进行中</span></div>
              <div v-if="stats.count > 0" class="result-bar" role="img" :aria-label="distributionLabel">
                <span v-for="item in distribution" :key="item.key" :class="item.key" :style="{ width: `${item.percent}%` }" />
              </div>
              <div v-else class="result-bar empty-bar" />
              <div class="result-legend">
                <span v-for="item in distribution" :key="item.key"><i :class="item.key" />{{ item.label }}<strong>{{ formatCount(item.count) }}</strong></span>
              </div>
            </div>
            <div class="turn-summary">
              <div><span>有调用轮次</span><strong>{{ formatCount(stats.per_turn.turns_with_calls) }}<small>轮</small></strong></div>
              <div><span>单轮最高调用</span><strong>{{ formatCount(stats.per_turn.max_calls) }}<small>次</small></strong></div>
              <div><span>逻辑调用总数</span><strong>{{ formatCount(stats.per_turn.logical_count) }}<small>次</small></strong></div>
            </div>
          </section>

          <div v-if="stats.uncertain > 0" class="uncertain-notice" role="status">
            <span class="notice-icon">!</span>
            <div><strong>{{ formatCount(stats.uncertain) }} 次写入结果尚未确认</strong>
              <p>包含正在执行或需要核实的写入，请结合调用状态检查日历结果，避免重复操作。</p></div>
          </div>
        </template>

        <section class="panel tool-panel">
          <div class="section-heading"><div><h3>工具表现</h3><p>按已结束的实际尝试统计，重试分别计数。</p></div><span v-if="stats">{{ stats.tools.length }} 个工具</span></div>
          <div v-if="statsLoading" class="table-state" role="status">正在读取工具表现…</div>
          <div v-else-if="statsError" class="table-state">统计暂不可用，请重新加载。</div>
          <div v-else-if="!stats?.tools.length" class="table-state"><strong>暂无已结束的工具调用</strong><span>调整时间范围，或在对话中使用工具后刷新。</span></div>
          <div v-else class="table-scroll">
            <table>
              <thead><tr><th>工具</th><th class="numeric">调用次数</th><th class="numeric">成功率</th><th class="numeric">平均耗时</th><th class="numeric">P95 耗时</th><th class="numeric">超时</th><th class="numeric">失败</th><th class="numeric">取消</th></tr></thead>
              <tbody><tr v-for="tool in sortedTools" :key="tool.tool_name">
                <td><strong class="tool-label">{{ toolLabel(tool.tool_name) }}</strong><code>{{ tool.tool_name }}</code></td>
                <td class="numeric">{{ formatCount(tool.count) }}</td>
                <td class="numeric"><span :class="{ 'warning-text': tool.success_rate !== null && tool.success_rate < 0.95 }">{{ formatRate(tool.success_rate) }}</span></td>
                <td class="numeric">{{ formatDuration(tool.avg_ms) }}</td><td class="numeric">{{ formatDuration(tool.p95_ms) }}</td>
                <td class="numeric" :class="{ 'warning-text': tool.timeout > 0 }">{{ formatCount(tool.timeout) }}</td>
                <td class="numeric" :class="{ 'danger-text': tool.error > 0 }">{{ formatCount(tool.error) }}</td><td class="numeric">{{ formatCount(tool.cancelled) }}</td>
              </tr></tbody>
            </table>
          </div>
        </section>

        <section class="panel calls-panel">
          <div class="section-heading"><div><h3>会话调用明细</h3><p>{{ sessionId ? '所选会话全部历史，最新记录优先；不受上方时间范围影响。' : '选择一个会话，查看每次尝试的执行状态与耗时。' }}</p></div><span v-if="calls.length">已加载 {{ calls.length }} 条</span></div>
          <div v-if="!sessionId" class="table-state"><strong>先选择一个会话</strong><span>支持按会话标题搜索，也可以直接输入会话 ID。</span></div>
          <div v-else-if="callsLoading && !calls.length" class="table-state" role="status">正在读取调用记录…</div>
          <template v-else>
            <div v-if="calls.length" class="table-scroll">
              <table class="calls-table">
                <thead><tr><th>开始时间</th><th>工具 / Agent</th><th>执行状态</th><th class="numeric">耗时 / 上限</th><th class="numeric">尝试序号</th><th>轮次 / 错误码</th></tr></thead>
                <tbody><tr v-for="call in calls" :key="call.attempt_id">
                  <td class="time-cell">{{ formatDate(call.started_at) }}<span>{{ call.read_only ? '只读查询' : '写入操作' }}</span></td>
                  <td><strong class="tool-label">{{ toolLabel(call.tool_name) }}</strong><code>{{ call.tool_name }} · {{ agentLabel(call.agent) }}</code></td>
                  <td><span class="status-tag" :class="statusClass(call.status)">{{ statusLabel(call.status) }}</span>
                    <span v-if="call.outcome === 'uncertain'" class="outcome-note">{{ call.status === 'running' ? '结果未确认' : '写入待核实' }}</span></td>
                  <td class="numeric">{{ formatDuration(call.elapsed_ms) }}<span>上限 {{ formatDuration(call.timeout_ms) }}</span></td>
                  <td class="numeric">第 {{ call.attempt_no }} 次</td>
                  <td><code class="turn-id" :title="`轮次：${call.turn_id}\n尝试：${call.attempt_id}\n逻辑调用：${call.logical_call_id}`">{{ call.turn_id.slice(0, 12) }}</code><span v-if="call.error_code" class="error-code" :title="call.error_code">{{ call.error_code }}</span></td>
                </tr></tbody>
              </table>
            </div>
            <div v-else-if="!callsError" class="table-state"><strong>该会话暂无调用记录</strong><span>仅展示埋点功能启用后记录的实际工具尝试。</span></div>
            <div v-if="callsError" class="calls-error" role="alert"><span>调用记录加载失败。</span><button class="text-button" @click="loadCalls(calls.length > 0)">重试</button></div>
            <div v-else-if="hasMore" class="load-more"><button class="outline-button" :disabled="callsLoading" @click="loadCalls(true)">{{ callsLoading ? '正在加载…' : '加载更早记录' }}</button></div>
          </template>
        </section>

        <footer class="metrics-note">成功率、平均与 P95 耗时基于已结束尝试，包含失败、超时和取消。单轮平均仅统计有工具调用的轮次；无样本时显示「—」。</footer>
      </template>
    </div>
    <AuthModal v-if="!isLoggedIn && showAuthModal" @success="showAuthModal = false" @close="showAuthModal = false" />
  </section>
</template>

<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue';
import { ElOption, ElSelect } from 'element-plus';
import { getSessionsAPI, type ChatSessionVO } from '../../../api/chat';
import { getToolCallsAPI, getToolStatsAPI, type ToolCallItem, type ToolStats } from '../../../api/toolMonitoring';
import { tokenRef } from '../../../utils/auth';
import AppIcon from '../../LayoutView/components/AppIcon.vue';
import AuthModal from '../../../components/AuthModal.vue';

const showAuthModal = ref(false);
const periods = [{ label: '最近 24 小时', hours: 24 }, { label: '最近 7 天', hours: 168 }, { label: '最近 30 天', hours: 720 }];
const hours = ref(24);
const sessionId = ref('');
const sessions = ref<ChatSessionVO[]>([]);
const sessionsLoading = ref(false);
const sessionsError = ref(false);
const stats = ref<ToolStats | null>(null);
const statsLoading = ref(false);
const statsError = ref(false);
const updatedAt = ref<Date | null>(null);
const calls = ref<ToolCallItem[]>([]);
const callsLoading = ref(false);
const callsError = ref(false);
const hasMore = ref(false);
const nextBeforeId = ref<number | null>(null);
let statsRequest = 0, callsRequest = 0, sessionsRequest = 0;
const isLoggedIn = computed(() => !!tokenRef.value);
const refreshing = computed(() => statsLoading.value || callsLoading.value);
const periodLabel = computed(() => periods.find(period => period.hours === hours.value)?.label);
const sortedTools = computed(() => [...(stats.value?.tools || [])].sort((a, b) => b.count - a.count));

const formatCount = (value?: number | null) => value == null ? '—' : value.toLocaleString('zh-CN');
const formatRate = (value?: number | null) => value == null ? '—' : `${(value * 100).toFixed(1)}%`;
const formatDuration = (value?: number | null) => value == null ? '—' : value >= 1000 ? `${(value / 1000).toFixed(2)} s` : `${value.toLocaleString('zh-CN', { maximumFractionDigits: 1 })} ms`;
const formatClock = (value: Date) => value.toLocaleTimeString('zh-CN', { hour12: false });
const formatDate = (value: string) => new Date(value).toLocaleString('zh-CN', { hour12: false });
const toolNames: Record<string, string> = { queryDayDetail: '查询日程', queryTodoList: '查询待办', queryMonthCount: '查询月历', queryScheduleRange: '查询区间日程', queryPreferences: '读取用户偏好', createTodo: '创建待办', batchCreateTodos: '批量同步规划', updateTodo: '修改待办', deleteTodo: '删除待办', toggleTodoDate: '更新完成状态', saveDailyNote: '保存日记', removeTodoDay: '移除待办日期', addTodoDay: '添加待办日期' };
const toolLabel = (name: string) => toolNames[name] || name;
const agentLabel = (name: string) => ({ chat: '对话', planner: '规划', executor: '执行', unknown: '未标记' })[name] || name;
const statuses: Record<string, string> = { success: '成功', timeout: '超时', error: '失败', cancelled: '已取消', running: '执行中' };
const statusLabel = (status: string) => statuses[status] || '未知状态';
const statusClass = (status: string) => status in statuses ? status : 'unknown';

const metricCards = computed(() => [
  { label: '实际调用次数', value: formatCount(stats.value?.count), unit: '次', caption: '包含首次尝试与重试', attention: false },
  { label: '调用成功率', value: formatRate(stats.value?.completed.success_rate), unit: '', caption: '成功尝试 / 已结束尝试', attention: false },
  { label: '平均耗时', value: formatDuration(stats.value?.completed.avg_ms), unit: '', caption: '每次实际尝试的平均耗时', attention: false },
  { label: 'P95 耗时', value: formatDuration(stats.value?.completed.p95_ms), unit: '', caption: '95% 的已结束尝试不超过此值', attention: false },
  { label: '单轮平均调用', value: stats.value?.per_turn.avg_calls == null ? '—' : stats.value.per_turn.avg_calls.toLocaleString('zh-CN', { maximumFractionDigits: 1 }), unit: '次', caption: '仅统计有工具调用的轮次', attention: false },
  { label: '结果待确认写入', value: formatCount(stats.value?.uncertain), unit: '次', caption: '包含执行中与待核实写入', attention: (stats.value?.uncertain || 0) > 0 },
]);
const distribution = computed(() => ['success', 'timeout', 'error', 'cancelled', 'running'].map(key => {
  const count = key === 'running' ? stats.value?.running || 0 : stats.value?.completed[key as 'success' | 'timeout' | 'error' | 'cancelled'] || 0;
  return { key, label: statusLabel(key), count, percent: stats.value?.count ? count / stats.value.count * 100 : 0 };
}));
const distributionLabel = computed(() => distribution.value.map(item => `${item.label} ${item.count} 次`).join('，'));

async function loadStats() {
  const request = ++statsRequest;
  stats.value = null;
  updatedAt.value = null;
  statsError.value = false;
  statsLoading.value = false;
  if (!tokenRef.value) return;
  statsLoading.value = true;
  const end = new Date();
  try {
    const result = await getToolStatsAPI({ start: new Date(end.getTime() - hours.value * 3600000).toISOString(), end: end.toISOString(), ...(sessionId.value ? { sessionId: sessionId.value } : {}) });
    if (request !== statsRequest) return;
    stats.value = result;
    updatedAt.value = new Date();
  } catch {
    if (request === statsRequest) statsError.value = true;
  } finally {
    if (request === statsRequest) statsLoading.value = false;
  }
}

async function loadSessions() {
  const request = ++sessionsRequest;
  sessionsError.value = false;
  if (!tokenRef.value) { sessions.value = []; sessionsLoading.value = false; return; }
  sessionsLoading.value = true;
  try {
    const result = await getSessionsAPI();
    if (request === sessionsRequest) sessions.value = result;
  } catch {
    if (request === sessionsRequest) sessionsError.value = true;
  } finally {
    if (request === sessionsRequest) sessionsLoading.value = false;
  }
}

async function loadCalls(append = false) {
  const request = ++callsRequest;
  callsError.value = false;
  callsLoading.value = false;
  if (!append) { calls.value = []; nextBeforeId.value = null; hasMore.value = false; }
  if (!tokenRef.value || !sessionId.value) return;
  callsLoading.value = true;
  try {
    const page = await getToolCallsAPI({ sessionId: sessionId.value, limit: 30, ...(append && nextBeforeId.value !== null ? { beforeId: nextBeforeId.value } : {}) });
    if (request !== callsRequest) return;
    calls.value = append ? [...calls.value, ...page.items] : page.items;
    hasMore.value = page.has_more && page.next_before_id !== null;
    nextBeforeId.value = page.next_before_id;
  } catch {
    if (request === callsRequest) callsError.value = true;
  } finally {
    if (request === callsRequest) callsLoading.value = false;
  }
}

function refreshAll() {
  void loadSessions();
  void loadStats();
  void loadCalls();
}

// 账号切换先同步清理，再发新请求；请求序号阻止旧账号/旧筛选结果覆盖页面。
watch(tokenRef, () => {
  ++statsRequest; ++callsRequest; ++sessionsRequest;
  stats.value = null; calls.value = []; sessions.value = [];
  updatedAt.value = null; sessionId.value = '';
  void loadSessions();
}, { immediate: true, flush: 'sync' });
watch([hours, sessionId, tokenRef], () => void loadStats(), { immediate: true });
watch([sessionId, tokenRef], () => void loadCalls(), { immediate: true });
onUnmounted(() => { ++statsRequest; ++callsRequest; ++sessionsRequest; });
</script>

<style scoped>
.tool-monitoring-panel { min-width: 0; color: #27272a; }
.operations-inner { max-width: 1240px; margin: 0 auto; }
.page-heading { display: flex; justify-content: space-between; align-items: center; gap: 20px; margin-bottom: 28px; }
.eyebrow { display: flex; align-items: center; gap: 10px; font-size: 12px; font-weight: 600; color: #71717a; }
.account-tag { padding: 3px 7px; border: 1px solid #e4e4e7; border-radius: 5px; font-size: 10px; font-weight: 400; color: #71717a; background: #fff; }
h1 { font-size: 28px; letter-spacing: -.7px; margin: 9px 0; color: #18181b; }
.page-heading p { font-size: 13px; color: #71717a; margin: 0; line-height: 1.7; }
button { font-family: inherit; cursor: pointer; transition: background .15s, border-color .15s; }
button:disabled { cursor: default; opacity: .5; }
button:focus-visible { outline: 2px solid #64748b; outline-offset: 3px; }
.refresh-button, .outline-button, .primary-button { display: inline-flex; justify-content: center; align-items: center; gap: 7px; border: 1px solid #dedee3; border-radius: 8px; background: #fff; padding: 9px 13px; font-size: 12px; color: #3f3f46; white-space: nowrap; }
.refresh-button:hover:not(:disabled), .outline-button:hover:not(:disabled) { background: #f4f4f5; border-color: #bcbcc4; }
.refresh-button .app-icon { width: 14px; height: 14px; }
.primary-button { background: #18181b; border-color: #18181b; color: #fff; }
.filter-bar { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 16px; background: #fff; border: 1px solid #e4e4e7; border-radius: 10px; padding: 12px; }
.time-presets { display: flex; gap: 4px; }
.time-presets button { padding: 8px 12px; border: 0; background: transparent; border-radius: 6px; color: #71717a; font-size: 12px; white-space: nowrap; }
.time-presets button:hover { background: #f4f4f5; }
.time-presets button.selected { background: #18181b; color: #fff; }
.session-filter { display: flex; align-items: center; gap: 10px; min-width: 0; }
.session-filter label { font-size: 12px; color: #71717a; white-space: nowrap; }
.session-filter :deep(.el-select) { width: 260px; --el-color-primary: #52525b; }
.session-filter :deep(.el-select__wrapper) { min-height: 33px; font-size: 12px; border-radius: 6px; }
.snapshot-line { display: flex; justify-content: space-between; gap: 12px; margin: 16px 1px; color: #a1a1aa; font-size: 11px; }
.filter-hint { color: #a16207; font-size: 12px; }
.metric-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; margin-bottom: 16px; }
.metric-card { min-width: 0; background: #fff; border: 1px solid #e4e4e7; border-radius: 10px; padding: 19px 20px 17px; }
.metric-card.attention { border-color: #f2d4a5; background: #fffdf8; }
.metric-label { color: #71717a; font-size: 12px; }
.metric-value { color: #18181b; font-size: 30px; font-weight: 650; letter-spacing: -1px; margin: 12px 0 10px; font-variant-numeric: tabular-nums; }
.metric-value small { color: #a1a1aa; font-size: 12px; font-weight: 400; margin-left: 6px; letter-spacing: 0; }
.attention .metric-value { color: #b45309; }
.metric-caption { font-size: 11px; color: #a1a1aa; }
.is-loading { opacity: .3; animation: pulse 1s ease-in-out infinite alternate; }
.panel { border: 1px solid #e4e4e7; border-radius: 10px; background: #fff; overflow: hidden; margin-bottom: 18px; }
.execution-panel { padding: 20px; display: flex; align-items: center; gap: 24px; }
.execution-results { flex: 1; min-width: 0; }
.section-heading { display: flex; justify-content: space-between; align-items: center; gap: 16px; padding: 20px; }
.section-heading h3 { margin: 0; font-size: 14px; font-weight: 650; color: #27272a; }
.section-heading p { margin: 6px 0 0; font-size: 11px; color: #a1a1aa; line-height: 1.6; }
.section-heading > span, .section-heading.compact > span { font-size: 11px; color: #a1a1aa; white-space: nowrap; }
.section-heading.compact { padding: 0; margin-bottom: 14px; }
.result-bar { display: flex; height: 7px; border-radius: 5px; overflow: hidden; background: #f4f4f5; gap: 2px; }
.result-bar > span { flex-shrink: 1; }
.success { background: #22a06b; }
.timeout { background: #e5a34a; }
.error { background: #e26767; }
.cancelled { background: #b6b6c0; }
.running { background: #7a99d6; }
.result-legend { display: flex; flex-wrap: wrap; gap: 14px; margin-top: 12px; }
.result-legend > span { display: inline-flex; align-items: center; gap: 5px; font-size: 11px; color: #71717a; }
.result-legend i { width: 6px; height: 6px; border-radius: 2px; }
.result-legend strong { color: #3f3f46; font-size: 11px; font-weight: 500; margin-left: 3px; }
.turn-summary { display: flex; flex-shrink: 0; gap: 24px; border-left: 1px solid #eeeeef; padding-left: 24px; }
.turn-summary > div { display: flex; flex-direction: column; gap: 10px; }
.turn-summary span { color: #a1a1aa; font-size: 11px; }
.turn-summary strong { color: #3f3f46; font-size: 19px; font-weight: 600; font-variant-numeric: tabular-nums; }
.turn-summary small { color: #a1a1aa; margin-left: 5px; font-size: 10px; font-weight: 400; }
.uncertain-notice { display: flex; gap: 12px; padding: 15px 18px; border: 1px solid #f2dfb7; border-radius: 9px; color: #96631e; background: #fffbf2; margin-bottom: 18px; }
.notice-icon { display: grid; place-items: center; flex-shrink: 0; margin-top: 1px; width: 17px; height: 17px; border: 1px solid #d6a254; border-radius: 50%; font-size: 11px; font-weight: 700; }
.uncertain-notice strong { font-size: 12px; font-weight: 600; }
.uncertain-notice p { margin: 5px 0 0; font-size: 11px; line-height: 1.7; color: #a47c40; }
.table-scroll { overflow-x: auto; }
table { border-collapse: collapse; width: 100%; font-size: 12px; text-align: left; white-space: nowrap; }
th { background: #fafafa; padding: 11px 20px; font-size: 11px; font-weight: 500; color: #a1a1aa; border-top: 1px solid #f1f1f3; border-bottom: 1px solid #eeeeef; }
td { padding: 15px 20px; border-bottom: 1px solid #f1f1f3; color: #52525b; font-variant-numeric: tabular-nums; }
tbody tr:last-child td { border-bottom: 0; }
tbody tr:hover { background: #fcfcfd; }
.numeric { text-align: right; }
.tool-label { display: block; font-size: 12px; font-weight: 550; color: #3f3f46; }
td code, td > span:not(.status-tag) { display: block; color: #a1a1aa; font-size: 10px; margin-top: 5px; }
td code { font-family: ui-monospace, SFMono-Regular, Consolas, monospace; }
td .warning-text, .warning-text { color: #b77928 !important; }
td .danger-text, .danger-text { color: #d25757 !important; }
td.numeric > .warning-text { font-size: 12px; margin-top: 0; }
.time-cell { font-size: 11px; }
.status-tag { display: inline-flex; align-items: center; border-radius: 5px; padding: 4px 7px; font-size: 10px; }
.status-tag.success { color: #27825d; background: #eef8f2; }
.status-tag.timeout { color: #ae792f; background: #fff6e9; }
.status-tag.error { color: #ce5d5d; background: #fdf0f0; }
.status-tag.cancelled, .status-tag.unknown { color: #85858f; background: #f4f4f5; }
.status-tag.running { color: #6584ba; background: #eef3fc; }
td .outcome-note { color: #b77928 !important; }
.error-code { max-width: 180px; overflow: hidden; text-overflow: ellipsis; }
.turn-id { cursor: help; color: #71717a; margin-top: 0; }
.table-state { min-height: 150px; padding: 34px 20px; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 10px; color: #a1a1aa; font-size: 12px; text-align: center; }
.table-state strong { color: #71717a; font-weight: 500; }
.table-state span { font-size: 11px; line-height: 1.7; }
.load-more { display: flex; justify-content: center; padding: 16px; border-top: 1px solid #f1f1f3; }
.calls-error { padding: 18px 20px; display: flex; justify-content: center; gap: 12px; color: #b45b5b; font-size: 12px; }
.text-button { background: none; border: 0; color: #52525b; padding: 0 3px; font-size: 12px; text-decoration: underline; text-underline-offset: 3px; }
.error-panel { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 22px; border: 1px solid #efcccc; background: #fff8f8; border-radius: 10px; color: #b45b5b; margin-bottom: 18px; }
.error-panel strong { font-size: 13px; }
.error-panel p { margin: 5px 0 0; font-size: 12px; }
.state-panel { padding: 90px 20px; display: flex; flex-direction: column; align-items: center; gap: 12px; text-align: center; border: 1px dashed #d4d4d8; border-radius: 12px; background: white; }
.state-panel .app-icon { color: #a1a1aa; width: 32px; height: 32px; }
.state-panel h3 { font-size: 16px; margin: 5px 0 0; }
.state-panel p { color: #a1a1aa; font-size: 12px; margin: 0 0 10px; }
.metrics-note { color: #a1a1aa; font-size: 10px; line-height: 1.9; padding: 0 2px; }
.spinning { animation: spin 1s linear infinite; }
@keyframes pulse { to { opacity: .65; } }
@keyframes spin { to { transform: rotate(360deg); } }
@media (min-width: 1600px) { .metric-grid { grid-template-columns: repeat(6, minmax(0, 1fr)); } .metric-card { padding: 20px 17px; } .metric-value { font-size: 27px; } }
@media (max-width: 1150px) { .execution-panel { flex-direction: column; align-items: stretch; } .turn-summary { border-left: 0; padding: 16px 0 0; border-top: 1px solid #eeeeef; justify-content: space-between; } .session-filter { flex: 1; justify-content: flex-end; } }
@media (max-width: 760px) { .metric-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } .metric-card { padding: 17px 15px; } .metric-value { font-size: 25px; } h1 { font-size: 24px; } .page-heading { align-items: flex-start; } .page-heading p { font-size: 12px; } .refresh-button { padding: 9px; } .session-filter { width: 100%; justify-content: flex-start; } .session-filter :deep(.el-select) { flex: 1; width: 0; } .time-presets { width: 100%; } .time-presets button { flex: 1; padding: 8px; } .filter-bar { gap: 12px; } .section-heading, .execution-panel { padding: 17px; } .section-heading.compact { flex-wrap: wrap; gap: 6px; } }
@media (prefers-reduced-motion: reduce) { .spinning, .is-loading { animation: none; } }
</style>
