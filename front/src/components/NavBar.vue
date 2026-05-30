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

      <div class="user-dropdown">
        <button
          v-if="!isLoggedIn"
          class="icon-btn highlight-btn"
          @click="$emit('login')"
        >
          <span class="text">去登录</span>
        </button>
        <template v-else>
          <button class="icon-btn highlight-btn">
            <span class="icon">👤</span>
            <span class="text">{{ userName }}</span>
          </button>

          <div class="dropdown-menu">
            <div class="dropdown-item" @click="handleProfile">个人中心</div>
            <div class="dropdown-item logout-text" @click="$emit('logout')">退出登录</div>
          </div>
        </template>
      </div>

      <button class="icon-btn" @click="$emit('help')" title="使用帮助">
        <span class="text">帮助</span>
        <span class="icon">❓</span>
      </button>

      <button class="icon-btn" @click="$emit('toggle-chat')">
        <span class="text">{{ chatOpen ? '收起对话' : '展开对话' }}</span>
        <span class="icon">{{ chatOpen ? '▶' : '◀' }}</span>
      </button>

    </div>
  </header>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { ElMessage } from 'element-plus';
// 引入响应式的用户信息
import { userInfoRef, tokenRef } from '../utils/auth';

defineProps<{
  todoOpen: boolean;
  chatOpen: boolean;
}>();

defineEmits<{
  (e: 'toggle-todo'): void;
  (e: 'toggle-chat'): void;
  (e: 'logout'): void;
  (e: 'login'): void;
  (e: 'help'): void;
}>();

const isLoggedIn = computed(() => !!tokenRef.value);

// 动态获取用户名，如果没有取到则显示默认文本
const userName = computed(() => {
  return userInfoRef.value?.userName || '未知用户';
});

// 点击个人中心
const handleProfile = () => {
  // 使用 Element Plus 的提示组件
  ElMessage({
    message: '个人中心功能待实现 🚧',
    type: 'info',
    duration: 2000 // 2秒后消失
  });
};
</script>

<style scoped>
.navbar {
  height: 64px;
  background-color: #f5eed8;
  border-bottom: 2px solid #d3c4a1;
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 0 24px;
  box-shadow: 0 2px 10px rgba(92, 75, 55, 0.08);
  z-index: 10;
  color: #5c4b37;
}

.nav-left, .nav-right { display: flex; align-items: center; gap: 12px; flex: 1; }
.nav-right { justify-content: flex-end; }
.nav-center { text-align: center; }

.logo { font-size: 1.3rem; font-weight: bold; margin: 0; letter-spacing: 2px; color: #5c4b37; }

/* 基础按钮样式 */
.icon-btn {
  display: flex; align-items: center; gap: 6px; padding: 8px 16px;
  border: 1px solid #d3c4a1; background: #fcf9ee; color: #5c4b37;
  border-radius: 6px; font-size: 14px; font-weight: 500;
  cursor: pointer; transition: all 0.2s ease;
}
.icon-btn:hover { background: #eaddc4; border-color: #c4b38d; }

/* ★ 区别于背景的高亮按钮 (深褐色) */
.highlight-btn {
  background-color: #5c4b37;
  color: #fdfae9;
  border: none;
}
.highlight-btn:hover { background-color: #4a3c2c; }

/* ---------------- 下拉菜单纯 CSS 实现 ---------------- */
.user-dropdown {
  position: relative; /* 定位锚点 */
  display: inline-block;
}

/* 默认隐藏下拉框 */
.dropdown-menu {
  display: none;
  position: absolute;
  top: 100%; /* 贴在按钮正下方 */
  right: 0;
  margin-top: 8px; /* 留一点间隙 */
  background: #fdfae9;
  border: 2px solid #d3c4a1;
  border-radius: 8px;
  min-width: 130px;
  box-shadow: 0 8px 16px rgba(92, 75, 55, 0.15);
  z-index: 100;
  overflow: hidden;
}

/* 核心机制：悬浮时显示下拉框，并且增加一个不可见的伪元素桥梁，防止鼠标移出按钮时下拉框消失 */
.user-dropdown:hover .dropdown-menu {
  display: block;
  animation: fadeIn 0.2s ease;
}
.user-dropdown::after {
  content: '';
  position: absolute;
  height: 15px;
  width: 100%;
  bottom: -15px;
}

.dropdown-item {
  padding: 12px 16px;
  cursor: pointer;
  color: #5c4b37;
  transition: background 0.2s;
  text-align: center;
  font-size: 14px;
}
.dropdown-item:hover { background: #eaddc4; font-weight: bold;}

/* 退出按钮用红色以示区别 */
.logout-text {
  color: #bc423f;
  border-top: 1px dashed #d3c4a1;
}
.logout-text:hover { color: white; background-color: #bc423f; }

@keyframes fadeIn {
  from { opacity: 0; transform: translateY(-10px); }
  to { opacity: 1; transform: translateY(0); }
}
</style>