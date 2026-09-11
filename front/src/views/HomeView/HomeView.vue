<template>
  <section class="calendar-center">
    <div class="workspace-heading">
      <div>
        <p class="section-label">Agent Calendar</p>
        <h2>智能日程工作台</h2>
      </div>
      <p>从待办、日历到对话，一屏完成日程安排。</p>
    </div>
    <div class="calendar-header">
      <button class="month-nav-btn" @click="prevMonth" title="上个月">‹</button>
      <input
          type="month"
          v-model="selectedMonthStr"
          class="month-selector"
          title="点击切换月份"
      />
      <button class="month-nav-btn" @click="nextMonth" title="下个月">›</button>
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
          <div
              v-if="day.isCurrentMonth && calendarTodoDots[day.fullDate]?.length"
              class="todo-dots"
          >
            <span
                v-for="(dot, di) in calendarTodoDots[day.fullDate].slice(0, 4)"
                :key="di"
                class="todo-dot"
                :style="{ background: dot.color }"
                :title="dot.title"
            ></span>
            <span
                v-if="calendarTodoDots[day.fullDate].length > 4"
                class="todo-dot-more"
            >+{{ calendarTodoDots[day.fullDate].length - 4 }}</span>
          </div>

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

  <TodoList
      :isOpen="isTodoExpanded"
      :selectedTodoId="selectedTodo?.todoId"
      @select="handleTodoSelect"
      @update="allTodos = $event"
  />
</template>

<script setup lang="ts">
import { ref, computed, watch, onMounted } from 'vue';
import { ElMessage } from 'element-plus';
import { tokenRef } from '../../utils/auth';
import { getMonthCountAPI, getDayTodosAPI, saveDailyNoteAPI, type DayTodosVO } from '../../api/calendar';
import type { TodoVO } from '../../api/todo';
import { solarToLunar } from '../../utils/lunar';

// --- 寮曞叆瀛愮粍浠?---
import TodoList from './components/TodoList.vue';
import DayDetailPanel from './components/DayDetailPanel.vue';

defineEmits<{ (e: 'refresh'): void }>();
const props = withDefaults(defineProps<{ isTodoExpanded?: boolean }>(), { isTodoExpanded: false });
const isTodoExpanded = computed(() => props.isTodoExpanded);

// ================= 鐘舵€佺鐞?=================
const hasToken = computed(() => !!tokenRef.value);
const selectedTodo = ref<TodoVO | null>(null);
const allTodos = ref<TodoVO[]>([]);
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

// ================= 鏃ュ巻鏍稿績閫昏緫 =================
const weekdays = ['日', '一', '二', '三', '四', '五', '六'];
const todayInit = new Date();
const selectedMonthStr = ref(
    `${todayInit.getFullYear()}-${String(todayInit.getMonth() + 1).padStart(2, '0')}`
);
// 鈽?鏂板锛氬垏鎹笂涓湀
const prevMonth = () => {
  const [year, month] = selectedMonthStr.value.split('-').map(Number);
  // JS 鐨?Date 涓湀浠芥槸 0 绱㈠紩鐨勶紝鎵€浠ュ綋鍓嶆湀鏄?month - 1锛屼笂涓湀鏄?month - 2
  const date = new Date(year, month - 2, 1);
  selectedMonthStr.value = `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}`;
};

// 鈽?鏂板锛氬垏鎹笅涓湀
const nextMonth = () => {
  const [year, month] = selectedMonthStr.value.split('-').map(Number);
  const date = new Date(year, month, 1); // 鍒氬ソ璺ㄨ繘涓嬩釜鏈?
  selectedMonthStr.value = `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}`;
};

// ---------------- 璇︽儏闈㈡澘鐩稿叧鐘舵€?----------------
const activeDate = ref<string | null>(null);
const isPanelOpen = ref(false); // 鐙珛鎺у埗涓婃媺鍔ㄧ敾
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

  // 琛ヤ笂鏈?
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

  // 鏈湀
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

  // 琛ヤ笅鏈?
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


// 鐐瑰嚮鏌愪竴澶╋紝灞曞紑璇︽儏闈㈡澘
const toggleEditPanel = async (day: any) => {
  if (!day.isCurrentMonth) return;

  if (activeDate.value === day.fullDate && isPanelOpen.value) {
    closeDayDetail();
    return;
  }

  activeDate.value = day.fullDate;
  isPanelOpen.value = true; // 瑙﹀彂涓婃媺鍔ㄧ敾
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
  isPanelOpen.value = false; // 瑙﹀彂涓嬫粦鏀惰捣鍔ㄧ敾锛屼繚鐣?activeDate 闃叉鍔ㄧ敾鏈熼棿鍐呭娑堝け
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

// ================= 鏃ュ巻褰╄壊鍦嗙偣锛堟寜寰呭姙棰滆壊锛?=================
const calendarTodoDots = computed(() => {
  const todos = allTodos.value;
  const [yearStr, monthStr] = selectedMonthStr.value.split('-');
  const prefix = `${yearStr}-${monthStr}-`;
  const map: Record<string, { color: string; title: string }[]> = {};
  for (const todo of todos) {
    for (const d of todo.dates) {
      if (d.startsWith(prefix)) {
        if (!map[d]) map[d] = [];
        map[d].push({ color: todo.color, title: todo.title });
      }
    }
  }
  return map;
});

// ================= 瑙掓爣鏁伴噺缁熻 =================
const monthCounts = ref<Record<string, number>>({});

const fetchMonthCounts = async () => {
  if (!hasToken.value) return; // 鈽?鏂板鎷︽埅锛氭湭鐧诲綍涓嶈姹?
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

// 鈽?鏂板锛氱洃鍚櫥褰曠姸鎬佸彉鍖栵紝鑱斿姩鍒锋柊鏃ュ巻瑙掓爣
watch(hasToken, (newVal) => {
  if (newVal) {
    fetchMonthCounts(); // 鐧诲綍鎴愬姛锛屾媺鍙栧綋鏈堣鏍?
  } else {
    monthCounts.value = {}; // 閫€鍑虹櫥褰曪紝娓呯┖瑙掓爣
    closeDayDetail(); // 鍏抽棴鍙兘姝ｅ紑鐫€鐨勮鎯呴潰鏉?
  }
});

onMounted(fetchMonthCounts);

</script>

<style scoped>
/* ---------------- 涓棿鏃ュ巻鍖?---------------- */
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
  gap: 24px; /* 鎸夐挳鍜屾湀浠介€夋嫨鍣ㄤ箣闂寸殑闂磋窛 */
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

/* 鈽?鏂板锛氭湀浠藉垏鎹㈡寜閽牱寮?*/
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
  transform: scale(1.05); /* 鎮诞鏃惰交寰斁澶?*/
}

.month-nav-btn:active {
  transform: scale(0.95); /* 鐐瑰嚮鏃惰交寰洖缂?*/
}

.calendar-wrapper {
  width: 100%;
  max-width: 980px;
  min-width: 500px;
  display: flex;
  flex-direction: column;
  flex-shrink: 0;
}

/* 鏄熸湡琛ㄥご */
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

/* ---------------- 鏃ュ巻缃戞牸浼樺寲鐗?---------------- */
.calendar-grid {
  display: grid;
  grid-template-columns: repeat(7, 1fr);
  border-top: 2px solid #d3c4a1;
  border-left: 2px solid #d3c4a1;
  background-color: #fcf9ee;
}

.day-cell {
  /* 鈽?鎵佸钩鍖栭珮搴﹁璁★紝閬垮厤鍦ㄥ灞忎笂鏍煎瓙杩囧ぇ */
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

/* 鍐滃巻淇℃伅 */
.lunar-info {
  margin-top: auto;
  font-size: 12px;
  line-height: 1.2;
}
.lunar-date { color: #b5a992; }
.lunar-festival { color: #bc423f; font-weight: bold; }
.lunar-term { color: #e67e22; font-weight: bold; }

/* 寰呭姙鏁伴噺瑙掓爣 */
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

/* 鏃ュ巻鏍煎瓙鍐呭僵鑹插渾鐐?*/
.todo-dots {
  display: flex;
  flex-wrap: wrap;
  gap: 3px;
  margin-top: 4px;
}
.todo-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
  box-shadow: 0 1px 2px rgba(0,0,0,0.15);
}
.todo-dot-more {
  font-size: 10px;
  color: #b5a992;
  line-height: 8px;
}

/* 鏍煎瓙鐘舵€佺被 */
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

/* Modern agent workspace refresh */
.calendar-center {
  padding: 26px 30px;
  background: #f8fafc;
  min-width: 0;
}

.workspace-heading {
  width: 100%;
  max-width: 1060px;
  display: flex;
  justify-content: space-between;
  align-items: end;
  gap: 24px;
  margin-bottom: 20px;
}

.workspace-heading h2 {
  margin: 0;
  color: #101828;
  font-size: 28px;
  line-height: 1.15;
  font-weight: 780;
  letter-spacing: 0;
}

.workspace-heading p {
  margin: 0;
  max-width: 360px;
  color: #667085;
  font-size: 14px;
  line-height: 1.55;
}

.section-label {
  color: #2563eb !important;
  font-size: 12px !important;
  font-weight: 800;
  text-transform: uppercase;
  letter-spacing: 0.08em !important;
  margin-bottom: 8px !important;
}

.calendar-header {
  gap: 14px;
  margin-bottom: 16px;
}

.month-selector {
  font-size: 18px;
  font-weight: 760;
  color: #101828;
  background: #ffffff;
  border: 1px solid rgba(15, 23, 42, 0.1);
  border-radius: 8px;
  padding: 9px 14px;
  box-shadow: 0 8px 24px rgba(15, 23, 42, 0.06);
}

.month-nav-btn {
  width: 38px;
  height: 38px;
  border-radius: 8px;
  background: #ffffff;
  border: 1px solid rgba(15, 23, 42, 0.1);
  color: #344054;
  box-shadow: 0 8px 24px rgba(15, 23, 42, 0.06);
}

.month-nav-btn:hover {
  background: #eff6ff;
  border-color: rgba(37, 99, 235, 0.35);
  color: #1d4ed8;
  transform: translateY(-1px);
}

.calendar-wrapper {
  max-width: 1060px;
  width: 100%;
  min-width: 0;
  overflow: hidden;
  border: 1px solid rgba(15, 23, 42, 0.08);
  border-radius: 12px;
  background: #ffffff;
  box-shadow: 0 18px 50px rgba(15, 23, 42, 0.08);
}

.weekdays {
  background: #f8fafc;
  border: 0;
  border-bottom: 1px solid #e4e7ec;
}

.weekday-cell {
  color: #667085;
  font-size: 12px;
  font-weight: 760;
}

.calendar-grid {
  border: 0;
  background: #ffffff;
}

.day-cell {
  background: #ffffff;
  border-right: 1px solid #eef2f7;
  border-bottom: 1px solid #eef2f7;
}

.day-cell:hover {
  background: #f8fafc;
}

.date-num {
  color: #101828;
  font-size: 15px;
  font-weight: 760;
}

.lunar-date {
  color: #98a2b3;
}

.lunar-festival {
  color: #dc2626;
}

.lunar-term {
  color: #0891b2;
}

.day-cell.not-current {
  background: #f9fafb;
  opacity: 0.72;
}

.day-cell.not-current .date-num {
  color: #98a2b3;
}

.day-cell.is-today {
  background: #eff6ff;
}

.day-cell.is-today .date-num {
  background-color: #2563eb;
}

.day-cell.is-active {
  background: #ecfeff;
  box-shadow: inset 0 0 0 2px #06b6d4;
}

.todo-toggle-btn {
  height: 38px;
  padding: 0 14px;
  border: 1px solid rgba(15, 23, 42, 0.1);
  border-radius: 8px;
  background: #ffffff;
  color: #344054;
  box-shadow: 0 8px 24px rgba(15, 23, 42, 0.06);
  cursor: pointer;
  font-size: 14px;
  font-weight: 650;
  transition: background 0.2s, border-color 0.2s, color 0.2s, transform 0.2s;
}

.todo-toggle-btn:hover {
  background: #eff6ff;
  border-color: rgba(37, 99, 235, 0.35);
  color: #1d4ed8;
  transform: translateY(-1px);
}

@media (max-width: 1180px) {
  .workspace-heading {
    align-items: flex-start;
    flex-direction: column;
    gap: 8px;
  }

  .calendar-center {
    padding: 22px;
  }
}
</style>
