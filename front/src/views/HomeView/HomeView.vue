<template>
  <div class="layout-container">
    <NavBar
        :todoOpen="isTodoOpen"
        :chatOpen="isChatOpen"
        @toggle-todo="isTodoOpen = !isTodoOpen"
        @toggle-chat="isChatOpen = !isChatOpen"
        @logout="handleLogout"
        @login="showAuthModal = true"
    />

    <main class="main-content">
      <TodoList
          :isOpen="isTodoOpen"
          :selectedTodoId="selectedTodo?.todoId"
          @select="handleTodoSelect"
      />

      <section class="calendar-center">
        <div class="calendar-header">
          <button class="month-nav-btn" @click="prevMonth" title="上个月">◀</button>
          <input
              type="month"
              v-model="selectedMonthStr"
              class="month-selector"
              title="点击切换月份"
          />
          <button class="month-nav-btn" @click="nextMonth" title="下个月">▶</button>
        </div>

        <div class="calendar-wrapper">
          <div class="weekdays">
            <div class="weekday-cell" v-for="day in weekdays" :key="day">{{ day }}</div>
          </div>

          <div class="calendar-grid">
            <div
                v-for="(day, index) in calendarDays"
                :key="index"
                class="day-cell"
                :class="{
                  'not-current': !day.isCurrentMonth,
                  'is-today': day.isToday,
                  'is-active': activeDate === day.fullDate,
                  'is-highlight': day.isCurrentMonth && highlightDates.has(day.fullDate)
                }"
                :style="day.isCurrentMonth && highlightDates.has(day.fullDate) && selectedTodo
                  ? { background: selectedTodo.color + '22' }
                  : {}"
                @click="toggleEditPanel(day)"
            >
              <span class="date-num">{{ day.date }}</span>
              <span
                  v-if="day.isCurrentMonth && monthCounts[day.fullDate]"
                  class="day-count"
                  :title="`这一天有${monthCounts[day.fullDate]}个待办`"
                  :style="{
                  background: getCountColor(monthCounts[day.fullDate]),
                  color: monthCounts[day.fullDate] >= 3 ? '#fdfae9' : '#5c4b37'
                }"
              >{{ monthCounts[day.fullDate] }}</span>

              <div class="lunar-info">
                <span v-if="day.festival" class="lunar-festival">{{ day.festival }}</span>
                <span v-else-if="day.solarTerm" class="lunar-term">{{ day.solarTerm }}</span>
                <span v-else class="lunar-date">{{ day.lunarDate }}</span>
              </div>
            </div>
          </div>
        </div>

        <DayDetailPanel
            :isOpen="isPanelOpen"
            :activeDate="activeDate"
            :todos="dayDetail?.todos || []"
            v-model="diaryContent"
            :saving="savingDiary"
            @close="closeDayDetail"
            @save="handleSaveDiary"
        />

      </section>

      <ChatPanel :isOpen="isChatOpen" />
    </main>

    <AuthModal v-if="!hasToken && showAuthModal" @success="handleLoginSuccess" @close="showAuthModal = false" />
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, onMounted } from 'vue';
import { ElMessage } from 'element-plus';
import { tokenRef, clearAuth } from '../../utils/auth';
import { logoutAPI } from '../../api/user';
import { getMonthCountAPI, getDayTodosAPI, saveDailyNoteAPI, type DayTodosVO } from '../../api/calendar';
import type { TodoVO } from '../../api/todo';
import { solarToLunar } from '../../utils/lunar';

// --- 引入抽离的子组件 ---
import AuthModal from '../../components/AuthModal.vue';
import NavBar from '../../components/NavBar.vue';
import TodoList from './components/TodoList.vue';
import ChatPanel from './components/ChatPanel.vue';
import DayDetailPanel from './components/DayDetailPanel.vue'; // 引入新抽离的面板组件

// ================= 状态管理 =================
const isTodoOpen = ref(true);
const isChatOpen = ref(true);
const hasToken = computed(() => !!tokenRef.value);
const showAuthModal = ref(!tokenRef.value);

const handleLoginSuccess = () => showAuthModal.value = false;

const selectedTodo = ref<TodoVO | null>(null);
const highlightDates = computed(() => {
  if (!selectedTodo.value) return new Set<string>();
  return new Set(selectedTodo.value.dates);
});

const handleTodoSelect = (todo: TodoVO) => {
  if (selectedTodo.value?.todoId === todo.todoId) {
    selectedTodo.value = null;
    return;
  }
  selectedTodo.value = todo;
  const firstDate = todo.dates?.[0] || todo.startDate;
  selectedMonthStr.value = firstDate.substring(0, 7);
};

const handleLogout = async () => {
  try { await logoutAPI(); } catch {}
  clearAuth();
  showAuthModal.value = true;
  ElMessage.success('已安全退出登录');
};


// ================= 日历核心逻辑 =================
const weekdays = ['日', '一', '二', '三', '四', '五', '六'];
const todayInit = new Date();
const selectedMonthStr = ref(
    `${todayInit.getFullYear()}-${String(todayInit.getMonth() + 1).padStart(2, '0')}`
);
// ★ 新增：切换上个月
const prevMonth = () => {
  const [year, month] = selectedMonthStr.value.split('-').map(Number);
  // JS 的 Date 中月份是 0 索引的，所以当前月是 month - 1，上个月是 month - 2
  const date = new Date(year, month - 2, 1);
  selectedMonthStr.value = `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}`;
};

// ★ 新增：切换下个月
const nextMonth = () => {
  const [year, month] = selectedMonthStr.value.split('-').map(Number);
  const date = new Date(year, month, 1); // 刚好跨进下个月
  selectedMonthStr.value = `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}`;
};

// ---------------- 详情面板相关状态 ----------------
const activeDate = ref<string | null>(null);
const isPanelOpen = ref(false); // 独立控制上拉动画
const dayDetail = ref<DayTodosVO | null>(null);
const diaryContent = ref('');
const savingDiary = ref(false);
const loadingDay = ref(false);

const calendarDays = computed(() => {
  const [yearStr, monthStr] = selectedMonthStr.value.split('-');
  const year = parseInt(yearStr);
  const month = parseInt(monthStr) - 1;
  const days = [];

  const firstDayOfMonth = new Date(year, month, 1);
  const firstDayWeekday = firstDayOfMonth.getDay();

  // 补上月
  const prevMonthLastDay = new Date(year, month, 0).getDate();
  const prevMonthDate = new Date(year, month, 0);
  const prevYear = prevMonthDate.getFullYear();
  const prevMonth = prevMonthDate.getMonth();
  for (let i = firstDayWeekday - 1; i >= 0; i--) {
    const d = prevMonthLastDay - i;
    const lunar = solarToLunar(prevYear, prevMonth + 1, d);
    days.push({
      date: d, isCurrentMonth: false, isToday: false,
      fullDate: `${prevYear}-${String(prevMonth + 1).padStart(2, '0')}-${String(d).padStart(2, '0')}`,
      ...lunar
    });
  }

  // 本月
  const daysInMonth = new Date(year, month + 1, 0).getDate();
  const today = new Date();
  for (let i = 1; i <= daysInMonth; i++) {
    const isToday = year === today.getFullYear() && month === today.getMonth() && i === today.getDate();
    const lunar = solarToLunar(year, month + 1, i);
    days.push({
      date: i, isCurrentMonth: true, isToday,
      fullDate: `${year}-${String(month + 1).padStart(2, '0')}-${String(i).padStart(2, '0')}`,
      ...lunar
    });
  }

  // 补下月
  const remainingDays = 42 - days.length;
  const nextMonthDate = new Date(year, month + 1, 1);
  const nextYear = nextMonthDate.getFullYear();
  const nextMonth = nextMonthDate.getMonth();
  for (let i = 1; i <= remainingDays; i++) {
    const lunar = solarToLunar(nextYear, nextMonth + 1, i);
    days.push({
      date: i, isCurrentMonth: false, isToday: false,
      fullDate: `${nextYear}-${String(nextMonth + 1).padStart(2, '0')}-${String(i).padStart(2, '0')}`,
      ...lunar
    });
  }
  return days;
});


// 点击某一天，展开详情面板
const toggleEditPanel = async (day: any) => {
  if (!day.isCurrentMonth) return;

  if (activeDate.value === day.fullDate && isPanelOpen.value) {
    closeDayDetail();
    return;
  }

  activeDate.value = day.fullDate;
  isPanelOpen.value = true; // 触发上拉动画
  await fetchDayDetail(day.fullDate);
};

const fetchDayDetail = async (date: string) => {
  loadingDay.value = true;
  try {
    const data = await getDayTodosAPI(date);
    dayDetail.value = data;
    diaryContent.value = data.dailyNote || '';
  } catch {
    dayDetail.value = null;
  } finally {
    loadingDay.value = false;
  }
};

const closeDayDetail = () => {
  isPanelOpen.value = false; // 触发下滑收起动画，保留 activeDate 防止动画期间内容消失
};

const handleSaveDiary = async () => {
  if (!activeDate.value) return;
  savingDiary.value = true;
  try {
    await saveDailyNoteAPI(activeDate.value, diaryContent.value);
    ElMessage.success('日记已保存');
  } catch {
  } finally {
    savingDiary.value = false;
  }
};

// ================= 角标数量统计 =================
const monthCounts = ref<Record<string, number>>({});

const fetchMonthCounts = async () => {
  const [yearStr, monthStr] = selectedMonthStr.value.split('-');
  try {
    const list = await getMonthCountAPI(parseInt(yearStr), parseInt(monthStr));
    const map: Record<string, number> = {};
    for (const item of list) map[item.date] = item.count;
    monthCounts.value = map;
  } catch {}
};

watch(selectedMonthStr, () => {
  closeDayDetail();
  fetchMonthCounts();
});

onMounted(fetchMonthCounts);

const getCountColor = (count: number) => {
  const ratio = Math.min(count, 5) / 5;
  return `rgba(92, 75, 55, ${0.25 + ratio * 0.7})`;
};
</script>

<style scoped>
/* ---------------- 基础布局 ---------------- */
.layout-container {
  width: 100vw;
  height: 100vh;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background-color: #f5f7fa;
}

.main-content {
  flex: 1;
  display: flex;
  overflow: hidden;
}

/* ---------------- 中间日历区 ---------------- */
.calendar-center {
  flex: 1;
  padding: 30px 40px;
  display: flex;
  flex-direction: column;
  align-items: center;
  overflow-y: auto;
  background-color: #Fdfae9;
}

.calendar-header {
  display: flex;
  justify-content: center;
  align-items: center;
  gap: 24px; /* 按钮和月份选择器之间的间距 */
  margin-bottom: 20px;
}

.month-selector {
  font-size: 2rem;
  font-weight: bold;
  color: #5c4b37;
  background: transparent;
  border: none;
  cursor: pointer;
  outline: none;
  font-family: inherit;
}
.month-selector::-webkit-calendar-picker-indicator { cursor: pointer; }

/* ★ 新增：月份切换按钮样式 */
.month-nav-btn {
  background: #fcf9ee;
  border: 2px solid #d3c4a1;
  color: #5c4b37;
  width: 40px;
  height: 40px;
  border-radius: 50%;
  cursor: pointer;
  display: flex;
  justify-content: center;
  align-items: center;
  font-size: 14px;
  transition: all 0.2s;
  box-shadow: 0 4px 8px rgba(92, 75, 55, 0.05);
}

.month-nav-btn:hover {
  background: #eaddc4;
  border-color: #5c4b37;
  transform: scale(1.05); /* 悬浮时轻微放大 */
}

.month-nav-btn:active {
  transform: scale(0.95); /* 点击时轻微回缩 */
}

.calendar-wrapper {
  width: 100%;
  max-width: 980px;
  min-width: 500px;
  display: flex;
  flex-direction: column;
  flex-shrink: 0;
}

/* 星期表头 */
.weekdays {
  display: grid;
  grid-template-columns: repeat(7, 1fr);
  background-color: #eaddc4;
  border: 2px solid #d3c4a1;
  border-bottom: none;
  flex-shrink: 0;
}

.weekday-cell {
  text-align: center;
  padding: 8px 0;
  font-weight: bold;
  color: #5c4b37;
}

/* ---------------- 日历网格优化版 ---------------- */
.calendar-grid {
  display: grid;
  grid-template-columns: repeat(7, 1fr);
  border-top: 2px solid #d3c4a1;
  border-left: 2px solid #d3c4a1;
  background-color: #fcf9ee;
}

.day-cell {
  /* ★ 扁平化高度设计，避免在宽屏上格子过大 */
  height: clamp(75px, 9vh, 110px);
  background: #fcf9ee;
  border-right: 2px solid #d3c4a1;
  border-bottom: 2px solid #d3c4a1;
  padding: 8px 10px;
  cursor: pointer;
  transition: all 0.2s;
  position: relative;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.day-cell:hover { background-color: #f5eed8; }

.date-num {
  font-size: 1.1rem;
  color: #5c4b37;
  font-weight: bold;
}

/* 农历信息 */
.lunar-info {
  margin-top: auto;
  font-size: 12px;
  line-height: 1.2;
}
.lunar-date { color: #b5a992; }
.lunar-festival { color: #bc423f; font-weight: bold; }
.lunar-term { color: #e67e22; font-weight: bold; }

/* 待办数量角标 */
.day-count {
  position: absolute;
  top: 8px;
  right: 8px;
  min-width: 22px;
  height: 22px;
  line-height: 22px;
  text-align: center;
  font-size: 12px;
  color: #5c4b37;
  background: #eaddc4;
  border-radius: 11px;
  font-weight: bold;
  box-sizing: border-box;
  box-shadow: 0 2px 4px rgba(92, 75, 55, 0.1);
}

/* 格子状态类 */
.day-cell.not-current { background: #f2ecd9; opacity: 0.7; }
.day-cell.not-current .date-num { color: #b5a992; }

.day-cell.is-today { background: #fdfae9; }
.day-cell.is-today .date-num {
  background-color: #bc423f;
  color: white;
  padding: 2px 6px;
  border-radius: 6px;
}

.day-cell.is-active {
  background: #eaddc4;
  box-shadow: inset 0 0 0 2px #5c4b37;
}
</style>