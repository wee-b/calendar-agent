<template>
  <teleport to="body">

    <transition name="fade">
      <div v-if="isOpen" class="sheet-overlay" @click="$emit('close')"></div>
    </transition>

    <transition name="slide-up">
      <div v-if="isOpen" class="sheet-panel">

        <div class="sheet-drag-handle"></div>

        <div class="panel-content">
          <div class="panel-header">
            <h3>{{ activeDate }} <span class="header-subtitle">日程与日记</span></h3>
            <button class="panel-close" @click="$emit('close')">✕</button>
          </div>

          <div class="panel-body">

            <div class="day-todos">
              <h4>当日待办</h4>
              <div class="todos-scroll-area">
                <template v-if="todos && todos.length > 0">
                  <div v-for="t in todos" :key="t.todoId" class="day-todo-item">
                    <span class="todo-dot" :style="{ background: t.color }"></span>
                    <div class="todo-info">
                      <span class="todo-name">{{ t.title }}</span>
                      <span v-if="t.dayContent" class="todo-content">{{ t.dayContent }}</span>
                    </div>
                    <span
                        class="todo-status"
                        :class="{ done: t.status === 1 }"
                        @click="handleToggleStatus(t)"
                        :title="t.status === 1 ? '点击取消完成' : '点击标记完成'"
                    >
                      {{ t.status === 1 ? '✓' : '○' }}
                    </span>
                  </div>
                </template>
                <div v-else class="empty-state">
                  <span class="empty-icon">☕</span>
                  <p>今天暂无待办任务，好好休息吧</p>
                </div>
              </div>
            </div>

            <div class="day-diary">
              <h4>生活手记</h4>
              <textarea
                  :value="modelValue"
                  @input="$emit('update:modelValue', ($event.target as HTMLTextAreaElement).value)"
                  class="diary-textarea"
                  placeholder="记录今天发生的事情、灵感或心情..."
              ></textarea>

              <div class="diary-actions">
                <span class="save-tip" v-show="saving">正在保存...</span>
                <button class="save-diary-btn" @click="$emit('save')" :disabled="saving">
                  {{ saving ? '保存中' : '保存日记' }}
                </button>
              </div>
            </div>

          </div>
        </div>
      </div>
    </transition>

  </teleport>
</template>

<script setup lang="ts">
import type { DayTodoItem } from '../../../api/calendar';
import { toggleTodoDateStatusAPI } from '../../../api/todo';

const props = defineProps<{
  isOpen: boolean;
  activeDate: string | null;
  todos: DayTodoItem[];
  modelValue: string; // 配合 v-model 用于日记内容双向绑定
  saving: boolean;
}>();

const emit = defineEmits<{
  (e: 'update:modelValue', value: string): void;
  (e: 'close'): void;
  (e: 'save'): void;
}>();

// ★ 处理待办状态切换 (乐观更新)
const handleToggleStatus = async (t: DayTodoItem) => {
  if (!props.activeDate) return;

  // 记录原始状态
  const originalStatus = t.status;

  // 1. 乐观更新：前端立刻变状态，不等待接口返回，体验更丝滑
  t.status = originalStatus === 1 ? 0 : 1;

  try {
    // 2. 发起真实网络请求
    await toggleTodoDateStatusAPI({
      todoId: t.todoId,
      todoDate: props.activeDate
    });
  } catch (error) {
    // 3. 如果请求失败，回退状态 (拦截器已处理错误提示)
    t.status = originalStatus;
  }
};
</script>

<style scoped>
/* ================= 遮罩层 ================= */
.sheet-overlay {
  position: fixed;
  top: 0; left: 0; right: 0; bottom: 0;
  background-color: rgba(92, 75, 55, 0.4);
  backdrop-filter: blur(3px);
  z-index: 2000;
}

/* ================= 上拉面板 ================= */
.sheet-panel {
  position: fixed;
  bottom: 0;
  left: 0;
  right: 0;

  /* ★ 宽度限制与居中，避免宽屏下显得过于笨重 */
  margin: 0 auto;
  width: 95%;
  max-width: 980px;

  height: 75vh;
  background: #fdfae9;
  border-top-left-radius: 24px;
  border-top-right-radius: 24px;
  border-top: 2px solid #d3c4a1;
  box-shadow: 0 -10px 40px rgba(92, 75, 55, 0.15);
  z-index: 2001;
  display: flex;
  flex-direction: column;
}

/* 顶部装饰小把手 */
.sheet-drag-handle {
  width: 48px;
  height: 6px;
  background-color: #d3c4a1;
  border-radius: 3px;
  margin: 12px auto;
  opacity: 0.6;
}

.panel-content {
  flex: 1;
  display: flex;
  flex-direction: column;
  padding: 10px 40px 30px;
  overflow: hidden;
}

.panel-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 24px;
  padding-bottom: 12px;
  border-bottom: 2px dashed #d3c4a1;
  flex-shrink: 0;
}

.panel-header h3 {
  color: #5c4b37;
  margin: 0;
  font-size: 1.6rem;
  display: flex;
  align-items: baseline;
  gap: 12px;
}

.header-subtitle {
  font-size: 1rem;
  color: #b5a992;
  font-weight: normal;
}

.panel-close {
  background: #fcf9ee;
  border: 1px solid #d3c4a1;
  color: #8c7a65;
  font-size: 18px;
  cursor: pointer;
  width: 36px;
  height: 36px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.2s;
}
.panel-close:hover {
  color: #fdfae9;
  background: #bc423f;
  border-color: #bc423f;
  transform: rotate(90deg);
}

/* ★ 双栏布局 */
.panel-body {
  flex: 1;
  display: grid;
  grid-template-columns: 1fr 1.2fr;
  gap: 40px;
  overflow: hidden;
}

.panel-body h4 {
  color: #5c4b37;
  font-size: 16px;
  margin: 0 0 16px 0;
  font-weight: bold;
  display: flex;
  align-items: center;
}
.panel-body h4::before { content: '📝'; margin-right: 8px; font-size: 14px; }

/* ---------------- 待办区域 ---------------- */
.day-todos {
  display: flex;
  flex-direction: column;
  height: 100%;
  overflow: hidden;
}

.todos-scroll-area {
  flex: 1;
  overflow-y: auto;
  padding-right: 12px;
}
.todos-scroll-area::-webkit-scrollbar { width: 6px; }
.todos-scroll-area::-webkit-scrollbar-thumb { background: #d3c4a1; border-radius: 4px; }

.day-todo-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px;
  background: #fcf9ee;
  border: 1px solid #eaddc4;
  border-radius: 10px;
  margin-bottom: 12px;
  transition: all 0.2s;
}
.day-todo-item:hover {
  border-color: #d3c4a1;
  box-shadow: 0 4px 12px rgba(92, 75, 55, 0.08);
  transform: translateX(2px);
}

.todo-dot { width: 14px; height: 14px; border-radius: 50%; flex-shrink: 0; }
.todo-info { flex: 1; min-width: 0; }
.todo-name { display: block; color: #5c4b37; font-size: 15px; font-weight: bold; }
.todo-content { display: block; color: #8c7a65; font-size: 13px; margin-top: 4px; }

/* ★ 状态圆圈样式升级 */
.todo-status {
  flex-shrink: 0;
  font-size: 22px;
  color: #d3c4a1;
  font-weight: bold;
  cursor: pointer; /* 鼠标变小手 */
  user-select: none; /* 防止双击选中文字 */
  transition: all 0.2s cubic-bezier(0.175, 0.885, 0.32, 1.275);
}
.todo-status:hover {
  transform: scale(1.2);
  color: #b5a992;
}
.todo-status.done {
  color: #5c4b37;
}

.empty-state {
  display: flex; flex-direction: column; align-items: center; justify-content: center;
  height: 140px; color: #b5a992; background: rgba(226, 216, 192, 0.2);
  border-radius: 10px; border: 1px dashed #d3c4a1;
}
.empty-icon { font-size: 28px; margin-bottom: 10px; }

/* ---------------- 日记区域 ---------------- */
.day-diary {
  display: flex;
  flex-direction: column;
  height: 100%;
}

.diary-textarea {
  flex: 1;
  width: 100%;
  padding: 20px;
  background: #fcf9ee;
  border: 1px solid #d3c4a1;
  border-radius: 12px;
  font-size: 15px;
  color: #5c4b37;
  line-height: 1.8;
  resize: none;
  box-sizing: border-box;
  font-family: inherit;
  transition: all 0.2s;
}
.diary-textarea:focus {
  border-color: #5c4b37;
  outline: none;
  background: #fff;
  box-shadow: 0 0 0 3px rgba(92, 75, 55, 0.1);
}

.diary-actions {
  display: flex; justify-content: flex-end; align-items: center; gap: 16px; margin-top: 20px; padding-bottom: 10px;
}

.save-diary-btn {
  padding: 12px 32px; background: #5c4b37; color: #fdfae9;
  border: none; border-radius: 8px; font-size: 15px; font-weight: bold;
  cursor: pointer; transition: all 0.2s; box-shadow: 0 4px 10px rgba(92, 75, 55, 0.2);
}
.save-diary-btn:hover:not(:disabled) { background: #4a3c2c; transform: translateY(-2px); box-shadow: 0 6px 14px rgba(92, 75, 55, 0.3); }
.save-diary-btn:active:not(:disabled) { transform: translateY(0); }
.save-diary-btn:disabled { opacity: 0.6; cursor: not-allowed; box-shadow: none; }


/* ================= Vue 过渡动画 ================= */

.fade-enter-active, .fade-leave-active { transition: opacity 0.3s ease; }
.fade-enter-from, .fade-leave-to { opacity: 0; }

.slide-up-enter-active, .slide-up-leave-active {
  transition: transform 0.4s cubic-bezier(0.25, 1, 0.5, 1);
}
.slide-up-enter-from, .slide-up-leave-to {
  transform: translateY(100%);
}
</style>