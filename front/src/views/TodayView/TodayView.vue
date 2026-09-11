<template>
  <section class="today-page">
    <div class="almanac-panel">
      <div class="date-block">
        <span class="solar-date">{{ almanac?.solarDate || todayDate }}</span>
        <h2>{{ almanac.lunarDate }}</h2>
        <p>{{ almanac.week }} · {{ almanac.jianXing }}日</p>
      </div>

      <div class="fortune-grid">
        <div class="fortune-section good">
          <div class="section-title">
            <span>宜</span>
            <strong>适合今天推进</strong>
          </div>
          <ul>
            <li v-for="item in almanac.yiList" :key="item">{{ item }}</li>
          </ul>
        </div>

        <div class="fortune-section avoid">
          <div class="section-title">
            <span>忌</span>
            <strong>今天尽量避开</strong>
          </div>
          <ul>
            <li v-for="item in almanac.jiList" :key="item">{{ item }}</li>
          </ul>
        </div>
      </div>

      <div class="lucky-row">
        <div>
          <span class="meta-label">幸运色</span>
          <strong>{{ almanac.luckyColor }}</strong>
        </div>
        <div>
          <span class="meta-label">幸运数字</span>
          <strong>{{ almanac.luckyNum }}</strong>
        </div>
      </div>
    </div>

    <div class="todos-panel">
      <header class="todos-header">
        <div>
          <span class="eyebrow">Today Tasks</span>
          <h2>今天的所有待办事项</h2>
        </div>
        <button class="refresh-btn" @click="refreshPage" :disabled="loading || almanacLoading">
          {{ loading ? '刷新中' : '刷新' }}
        </button>
      </header>

      <div v-if="loading" class="state-card">正在加载今日待办...</div>

      <div v-else-if="todayTodos.length === 0" class="empty-card">
        <span class="empty-mark">✓</span>
        <h3>今天暂时没有待办</h3>
        <p>可以去日历页创建待办，或直接开启一个新对话规划今天。</p>
      </div>

      <ul v-else class="todo-list">
        <li
          v-for="todo in todayTodos"
          :key="todo.todoId"
          class="todo-card"
          :class="{ done: todo.status === 1 }"
        >
          <span class="todo-dot" :style="{ background: todo.color }"></span>
          <div class="todo-copy">
            <strong>{{ todo.title }}</strong>
            <p v-if="todo.dayContent">{{ todo.dayContent }}</p>
          </div>
          <button
            class="status-btn"
            :class="{ done: todo.status === 1 }"
            @click="handleToggle(todo)"
            :title="todo.status === 1 ? '取消完成' : '标记完成'"
          >
            {{ todo.status === 1 ? '已完成' : '完成' }}
          </button>
        </li>
      </ul>
    </div>
  </section>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { ElMessage } from 'element-plus';
import { getDayTodosAPI, type DayTodoItem } from '../../api/calendar';
import { toggleTodoDateStatusAPI } from '../../api/todo';
import { getAlmanacDayAPI, type AlmanacDayVO } from '../../api/almanac';

const fallbackAlmanac: AlmanacDayVO = {
  solarDate: '',
  lunarDate: '今日黄历',
  week: '今天',
  jianXing: '平',
  traditionalYiList: [],
  traditionalJiList: [],
  yiList: ['整理计划', '专注学习', '推进待办', '早点休息'],
  jiList: ['长时间摸鱼', '熬夜硬撑', '临时拖延'],
  luckyColor: '天蓝',
  luckyNum: 5
};

const almanac = ref<AlmanacDayVO>(fallbackAlmanac);
const almanacLoading = ref(false);
const todayTodos = ref<DayTodoItem[]>([]);
const loading = ref(false);

const getTodayDate = () => {
  const now = new Date();
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`;
};

const todayDate = getTodayDate();

const fetchAlmanac = async () => {
  almanacLoading.value = true;
  try {
    almanac.value = await getAlmanacDayAPI(todayDate);
  } finally {
    almanacLoading.value = false;
  }
};

const fetchTodayTodos = async () => {
  loading.value = true;
  try {
    const data = await getDayTodosAPI(todayDate);
    todayTodos.value = data.todos || [];
  } finally {
    loading.value = false;
  }
};

const handleToggle = async (todo: DayTodoItem) => {
  const originalStatus = todo.status;
  todo.status = originalStatus === 1 ? 0 : 1;
  try {
    await toggleTodoDateStatusAPI({
      todoId: todo.todoId,
      todoDate: todayDate
    });
  } catch {
    todo.status = originalStatus;
    ElMessage.error('状态更新失败，请稍后重试');
  }
};

const refreshPage = async () => {
  await Promise.all([fetchAlmanac(), fetchTodayTodos()]);
};

onMounted(refreshPage);
</script>

<style scoped>
.today-page {
  width: 100%;
  height: 100%;
  min-width: 0;
  display: grid;
  grid-template-columns: minmax(320px, 0.92fr) minmax(360px, 1.08fr);
  gap: 22px;
  padding: 28px;
  overflow: hidden;
  background: #f8fafc;
}

.almanac-panel,
.todos-panel {
  min-width: 0;
  min-height: 0;
  border: 1px solid rgba(15, 23, 42, 0.08);
  border-radius: 14px;
  background: #ffffff;
  box-shadow: 0 18px 50px rgba(15, 23, 42, 0.08);
}

.almanac-panel {
  display: flex;
  flex-direction: column;
  padding: 30px;
  overflow: hidden;
}

.date-block {
  padding-bottom: 24px;
  border-bottom: 1px solid #e4e7ec;
}

.solar-date,
.eyebrow,
.meta-label {
  display: block;
  color: #667085;
  font-size: 13px;
  font-weight: 700;
}

.date-block h2 {
  margin: 10px 0 8px;
  color: #101828;
  font-size: clamp(28px, 4vw, 44px);
  line-height: 1.1;
  font-weight: 820;
}

.date-block p {
  margin: 0;
  color: #344054;
  font-size: 16px;
  font-weight: 650;
}

.fortune-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
  margin-top: 24px;
}

.fortune-section {
  border-radius: 12px;
  padding: 18px;
}

.fortune-section.good {
  background: #ecfdf3;
}

.fortune-section.avoid {
  background: #fff1f3;
}

.section-title {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 14px;
  color: #101828;
}

.section-title span {
  width: 34px;
  height: 34px;
  display: grid;
  place-items: center;
  border-radius: 50%;
  color: #ffffff;
  font-size: 18px;
  font-weight: 800;
}

.good .section-title span {
  background: #12b76a;
}

.avoid .section-title span {
  background: #f04438;
}

.section-title strong {
  font-size: 15px;
}

.fortune-section ul,
.todo-list {
  list-style: none;
  margin: 0;
  padding: 0;
}

.fortune-section li {
  color: #344054;
  font-size: 15px;
  line-height: 1.5;
  padding: 7px 0;
}

.lucky-row {
  margin-top: auto;
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
  padding-top: 24px;
}

.lucky-row > div {
  padding: 18px;
  border-radius: 12px;
  background: #eff6ff;
}

.lucky-row strong {
  display: block;
  margin-top: 6px;
  color: #1d4ed8;
  font-size: 24px;
}

.todos-panel {
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.todos-header {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  padding: 26px 28px 18px;
  border-bottom: 1px solid #e4e7ec;
}

.todos-header h2 {
  margin: 6px 0 0;
  color: #101828;
  font-size: 24px;
  font-weight: 800;
}

.refresh-btn,
.status-btn {
  height: 36px;
  border: 0;
  border-radius: 999px;
  cursor: pointer;
  font-weight: 750;
}

.refresh-btn {
  padding: 0 16px;
  color: #ffffff;
  background: linear-gradient(135deg, #2563eb, #0891b2);
}

.refresh-btn:disabled {
  cursor: default;
  opacity: 0.72;
}

.todo-list {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 18px;
}

.todo-card {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  gap: 14px;
  padding: 16px;
  border: 1px solid #e4e7ec;
  border-radius: 12px;
  background: #ffffff;
  transition: background 0.18s, border-color 0.18s, transform 0.18s;
}

.todo-card + .todo-card {
  margin-top: 12px;
}

.todo-card:hover {
  border-color: rgba(37, 99, 235, 0.25);
  background: #f8fafc;
  transform: translateY(-1px);
}

.todo-card.done {
  opacity: 0.72;
}

.todo-dot {
  width: 14px;
  height: 14px;
  border-radius: 50%;
}

.todo-copy {
  min-width: 0;
}

.todo-copy strong {
  display: block;
  color: #101828;
  font-size: 16px;
  line-height: 1.35;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.todo-copy p {
  margin: 5px 0 0;
  color: #667085;
  font-size: 14px;
  line-height: 1.45;
}

.status-btn {
  padding: 0 14px;
  color: #2563eb;
  background: #eff6ff;
}

.status-btn.done {
  color: #039855;
  background: #ecfdf3;
}

.state-card,
.empty-card {
  margin: 18px;
  border: 1px dashed #d0d5dd;
  border-radius: 12px;
  background: #f8fafc;
  color: #667085;
}

.state-card {
  padding: 22px;
}

.empty-card {
  flex: 1;
  min-height: 220px;
  display: grid;
  place-items: center;
  align-content: center;
  gap: 8px;
  text-align: center;
  padding: 28px;
}

.empty-mark {
  width: 44px;
  height: 44px;
  display: grid;
  place-items: center;
  border-radius: 50%;
  background: #ecfdf3;
  color: #039855;
  font-weight: 900;
}

.empty-card h3 {
  margin: 0;
  color: #101828;
}

.empty-card p {
  margin: 0;
  max-width: 320px;
  line-height: 1.5;
}

@media (max-width: 980px) {
  .today-page {
    grid-template-columns: 1fr;
    overflow-y: auto;
  }

  .almanac-panel,
  .todos-panel {
    min-height: 420px;
  }
}

@media (max-width: 640px) {
  .today-page {
    padding: 18px;
  }

  .fortune-grid,
  .lucky-row {
    grid-template-columns: 1fr;
  }

  .todo-card {
    grid-template-columns: auto minmax(0, 1fr);
  }

  .status-btn {
    grid-column: 2;
    justify-self: start;
  }
}
</style>
