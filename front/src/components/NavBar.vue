<template>
  <header class="navbar">
    <div class="nav-left">
      <button class="icon-btn" @click="$emit('toggle-todo')">
        <span class="icon">{{ todoOpen ? '◀' : '▶' }}</span>
        <span class="text">{{ todoOpen ? '收起待办' : '展开待办' }}</span>
      </button>
    </div>

    <div class="nav-center">
      <h1 class="logo">🗓️ 语音日历</h1>
    </div>

    <div class="nav-right">
      <button class="icon-btn text-btn" @click="$emit('logout')">退出登录</button>
      <button class="icon-btn" @click="$emit('toggle-chat')">
        <span class="text">{{ chatOpen ? '收起对话' : '展开对话' }}</span>
        <span class="icon">{{ chatOpen ? '▶' : '◀' }}</span>
      </button>
    </div>
  </header>
</template>

<script setup lang="ts">
// 接收父组件(HomeView)传来的状态
defineProps<{
  todoOpen: boolean;
  chatOpen: boolean;
}>();

// 定义向父组件发送的事件
defineEmits<{
  (e: 'toggle-todo'): void;
  (e: 'toggle-chat'): void;
  (e: 'logout'): void;
}>();
</script>

<style scoped>
/* 导航栏主体融入复古主题 */
.navbar {
  height: 64px;
  background-color: #f5eed8; /* 偏暖的纸张底色 */
  border-bottom: 2px solid #d3c4a1; /* 与日历网格一致的边框色 */
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 0 24px;
  box-shadow: 0 2px 10px rgba(92, 75, 55, 0.08); /* 褐色的微弱阴影 */
  z-index: 10;
  color: #5c4b37;
}

/* 区域布局 */
.nav-left, .nav-right {
  display: flex;
  align-items: center;
  gap: 12px;
  flex: 1; /* 两侧占满剩余空间，保证中间Logo绝对居中 */
}
.nav-right {
  justify-content: flex-end;
}
.nav-center {
  text-align: center;
}

/* Logo 样式 */
.logo {
  font-size: 1.3rem;
  font-weight: bold;
  margin: 0;
  letter-spacing: 2px; /* 增加字间距更有复古感 */
  color: #5c4b37;
}

/* 按钮通用样式 */
.icon-btn {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 16px;
  border: 1px solid #d3c4a1;
  background: #fcf9ee; /* 比背景稍亮的按钮底色 */
  color: #5c4b37;
  border-radius: 6px;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.2s ease;
}

.icon-btn:hover {
  background: #eaddc4;
  border-color: #c4b38d;
}

.icon-btn:active {
  transform: scale(0.98); /* 点击时微弱缩放 */
}

/* 退出按钮做成幽灵按钮（弱化视觉） */
.text-btn {
  background: transparent;
  border-color: transparent;
}
.text-btn:hover {
  background: rgba(92, 75, 55, 0.06);
  border-color: transparent;
}
</style>