<template>
  <header class="layout-navbar">
    <div class="nav-left">
      <button
        class="icon-btn sidebar-toggle"
        @click="$emit('toggle-sidebar')"
        :title="isSidebarOpen ? '隐藏历史对话' : '显示历史对话'"
      >
        <span></span>
      </button>
      <button class="new-chat-btn" @click="$emit('new-chat')">新对话</button>
    </div>

    <div class="nav-center">
      <h1>{{ title }}</h1>
      <p>AI 生成可能有误 注意核实</p>
    </div>

    <div class="nav-right">
      <button
        v-if="showTodoToggle"
        class="todo-toggle-btn"
        @click="$emit('toggle-todo')"
      >
        {{ isTodoExpanded ? '收起待办' : '展开待办' }}
      </button>
    </div>
  </header>
</template>

<script setup lang="ts">
defineProps<{
  title: string;
  isSidebarOpen: boolean;
  showTodoToggle: boolean;
  isTodoExpanded: boolean;
}>();

defineEmits<{
  (e: 'toggle-sidebar'): void;
  (e: 'new-chat'): void;
  (e: 'toggle-todo'): void;
}>();
</script>

<style scoped>
.layout-navbar {
  height: 78px;
  flex-shrink: 0;
  display: grid;
  grid-template-columns: 1fr auto 1fr;
  align-items: center;
  padding: 0 22px;
  border-bottom: 1px solid #eeeeef;
  background: #ffffff;
}

.nav-left,
.nav-right {
  display: flex;
  align-items: center;
  gap: 12px;
}

.nav-right {
  justify-content: flex-end;
}

.nav-center {
  text-align: center;
  min-width: 0;
}

.nav-center h1 {
  margin: 0;
  color: #111827;
  font-size: 18px;
  line-height: 1.25;
  font-weight: 750;
}

.nav-center p {
  margin: 4px 0 0;
  color: #d1d5db;
  font-size: 13px;
}

.icon-btn,
.new-chat-btn,
.todo-toggle-btn {
  height: 38px;
  border: 1px solid #dedee3;
  border-radius: 999px;
  background: #ffffff;
  color: #18181b;
  cursor: pointer;
  font-size: 15px;
  font-weight: 650;
}

.icon-btn {
  width: 38px;
  display: grid;
  place-items: center;
  border-radius: 12px;
}

.icon-btn span {
  width: 16px;
  height: 16px;
  position: relative;
  display: block;
  border: 2px solid currentColor;
  border-radius: 4px;
}

.icon-btn span::before {
  content: "";
  position: absolute;
  left: 4px;
  top: -2px;
  bottom: -2px;
  width: 2px;
  background: currentColor;
}

.new-chat-btn,
.todo-toggle-btn {
  padding: 0 18px;
}

.todo-toggle-btn {
  color: #ffffff;
  border-color: transparent;
  background: linear-gradient(135deg, #2563eb, #0891b2);
}

.icon-btn:hover,
.new-chat-btn:hover {
  background: #f4f4f5;
}

.todo-toggle-btn:hover {
  background: linear-gradient(135deg, #1d4ed8, #0e7490);
}

@media (max-width: 760px) {
  .layout-navbar {
    grid-template-columns: auto 1fr auto;
    padding: 0 14px;
  }

  .new-chat-btn {
    padding: 0 14px;
  }
}
</style>
