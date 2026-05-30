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
          <input
              type="month"
              v-model="selectedMonthStr"
              class="month-selector"
              title="点击切换月份"
          />
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

        <div class="inline-editor" :class="{ 'editor-open': activeDate !== null }">
          <div v-if="activeDate !== null" class="editor-content">
            <h3>编辑 {{ activeDate }} 的日程</h3>
            <p>这里是详情编辑区域，支持语音输入或手动修改日程信息...</p>
            <div class="actions">
              <button class="save-btn">保存日程</button>
              <button class="cancel-btn" @click="activeDate = null">取消</button>
            </div>
          </div>
        </div>
      </section>

      <ChatPanel :isOpen="isChatOpen" />

    </main>

    <AuthModal v-if="!hasToken && showAuthModal" @success="handleLoginSuccess" @close="showAuthModal = false" />
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, onMounted } from 'vue';

import AuthModal from '../../components/AuthModal.vue';
import { tokenRef, clearAuth } from '../../utils/auth';


// 引入抽离的组件 (注意这里的相对路径)
import NavBar from '../../components/NavBar.vue';
import TodoList from './components/TodoList.vue';
import ChatPanel from './components/ChatPanel.vue';
import {ElMessage} from "element-plus";
import { logoutAPI } from '../../api/user';
import { getMonthCountAPI } from '../../api/calendar';
import type { TodoVO } from '../../api/todo';
import { solarToLunar } from '../../utils/lunar';


// 侧边栏状态控制
const isTodoOpen = ref(true);
const isChatOpen = ref(true);


const hasToken = computed(() => !!tokenRef.value);
const showAuthModal = ref(!tokenRef.value);

const handleLoginSuccess = () => {
  showAuthModal.value = false;
};

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
  try {
    await logoutAPI();
  } catch {
    // 即使接口失败也清除本地凭证
  }
  clearAuth();
  showAuthModal.value = true;
  ElMessage.success('已安全退出登录');
};


// ---------------- 日历核心逻辑 ----------------

const weekdays = ['日', '一', '二', '三', '四', '五', '六'];
const todayInit = new Date();
const selectedMonthStr = ref(
    `${todayInit.getFullYear()}-${String(todayInit.getMonth() + 1).padStart(2, '0')}`
);
const activeDate = ref<string | null>(null);

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
      date: d,
      isCurrentMonth: false,
      isToday: false,
      fullDate: `${prevYear}-${String(prevMonth + 1).padStart(2, '0')}-${String(d).padStart(2, '0')}`,
      lunarDate: lunar.lunarDate,
      festival: lunar.festival,
      solarTerm: lunar.solarTerm,
    });
  }

  // 本月
  const daysInMonth = new Date(year, month + 1, 0).getDate();
  const today = new Date();
  for (let i = 1; i <= daysInMonth; i++) {
    const isToday = year === today.getFullYear() && month === today.getMonth() && i === today.getDate();
    const lunar = solarToLunar(year, month + 1, i);
    days.push({
      date: i,
      isCurrentMonth: true,
      isToday,
      fullDate: `${year}-${String(month + 1).padStart(2, '0')}-${String(i).padStart(2, '0')}`,
      lunarDate: lunar.lunarDate,
      festival: lunar.festival,
      solarTerm: lunar.solarTerm,
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
      date: i,
      isCurrentMonth: false,
      isToday: false,
      fullDate: `${nextYear}-${String(nextMonth + 1).padStart(2, '0')}-${String(i).padStart(2, '0')}`,
      lunarDate: lunar.lunarDate,
      festival: lunar.festival,
      solarTerm: lunar.solarTerm,
    });
  }
  return days;
});

const toggleEditPanel = (day: any) => {
  if (!day.isCurrentMonth) return;
  activeDate.value = activeDate.value === day.fullDate ? null : day.fullDate;
};

// 当月每日待办数量
const monthCounts = ref<Record<string, number>>({});

const fetchMonthCounts = async () => {
  const [yearStr, monthStr] = selectedMonthStr.value.split('-');
  try {
    const list = await getMonthCountAPI(parseInt(yearStr), parseInt(monthStr));
    const map: Record<string, number> = {};
    for (const item of list) {
      map[item.date] = item.count;
    }
    monthCounts.value = map;
  } catch {
    // 未登录时接口可能报错，忽略
  }
};

watch(selectedMonthStr, () => {
  activeDate.value = null;
  fetchMonthCounts();
});

onMounted(fetchMonthCounts);

const getCountColor = (count: number) => {
  // 1 → 浅，5+ → 深
  const ratio = Math.min(count, 5) / 5; // 0.2 ~ 1.0
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

/* ---------------- 中间日历区复古主题 ---------------- */
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
  text-align: center;
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
.month-selector::-webkit-calendar-picker-indicator {
  cursor: pointer;
}

.calendar-wrapper {
  width: 100%;
  max-width: 980px;
  min-width: 500px;
  display: flex;
  flex-direction: column;
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

/* 日历网格 */
.calendar-grid {
  display: grid;
  grid-template-columns: repeat(7, 1fr);
  border-top: 2px solid #d3c4a1;
  border-left: 2px solid #d3c4a1;
  margin-bottom: 20px;
}

.day-cell {
  aspect-ratio: 1;
  background: #fcf9ee;
  border-right: 2px solid #d3c4a1;
  border-bottom: 2px solid #d3c4a1;
  padding: 8px;
  cursor: pointer;
  transition: all 0.2s;
  position: relative;
  overflow: hidden;
}

.day-cell:hover {
  background-color: #f5eed8;
}

.date-num {
  font-size: 1.1rem;
  color: #5c4b37;
  font-weight: 500;
}

.lunar-info {
  margin-top: 4px;
  font-size: 11px;
  line-height: 1.3;
}

.lunar-date {
  color: #b5a992;
}

.lunar-festival {
  color: #bc423f;
  font-weight: bold;
}

.lunar-term {
  color: #e67e22;
  font-weight: bold;
}

.day-count {
  position: absolute;
  top: 6px;
  right: 6px;
  min-width: 20px;
  height: 20px;
  line-height: 20px;
  text-align: center;
  font-size: 11px;
  color: #5c4b37;
  background: #eaddc4;
  padding: 0 4px;
  border-radius: 4px;
  font-weight: bold;
  box-sizing: border-box;
}

/* 状态类 */
.day-cell.not-current { background: #f2ecd9; }
.day-cell.not-current .date-num { color: #b5a992; }

.day-cell.is-today { background: #f5eed8; }
.day-cell.is-today .date-num {
  background-color: #bc423f; /* 老日历红 */
  color: white;
  padding: 2px 6px;
  border-radius: 4px;
}

.day-cell.is-active {
  background: #eaddc4;
  box-shadow: inset 0 0 0 2px #5c4b37;
}



/* ---------------- 编辑面板 ---------------- */
.inline-editor {
  background: #fcf9ee;
  border: 2px dashed #d3c4a1;
  border-radius: 8px;
  overflow: hidden;
  max-height: 0;
  opacity: 0;
  transition: all 0.4s cubic-bezier(0.25, 0.8, 0.25, 1);
}

.inline-editor.editor-open {
  max-height: 400px;
  opacity: 1;
  margin-bottom: 20px;
  padding: 20px;
}

.editor-content h3 { color: #5c4b37; margin-top: 0; }
.editor-content p { color: #666; }

.actions { margin-top: 20px; display: flex; gap: 10px; }
.save-btn { background: #5c4b37; color: white; border: none; padding: 8px 16px; border-radius: 4px; cursor: pointer; transition: background 0.2s;}
.save-btn:hover { background: #4a3c2c; }
.cancel-btn { background: transparent; color: #5c4b37; border: 1px solid #d3c4a1; padding: 8px 16px; border-radius: 4px; cursor: pointer; transition: background 0.2s;}
.cancel-btn:hover { background: #eaddc4; }
</style>