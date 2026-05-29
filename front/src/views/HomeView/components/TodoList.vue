<template>
  <aside class="sidebar left-sidebar" :class="{ 'is-collapsed': !isOpen }">
    <div class="sidebar-content">
      <h2>待办清单</h2>
      <ul class="todo-items">
        <li>买咖啡豆</li>
        <li>准备下周周报</li>
        <li>晚上 8 点健身</li>
      </ul>
    </div>
  </aside>
</template>

<script setup lang="ts">
// 接收父组件传递过来的展开/收起状态
defineProps<{
  isOpen: boolean;
}>();
</script>

<style scoped>
/* 侧边栏基础结构 */
.sidebar {
  width: 20%;
  min-width: 280px;
  max-width: 400px;
  background-color: #fdfae9; /* 配合日历的纸张底色 */
  transition: all 0.3s cubic-bezier(0.25, 0.8, 0.25, 1);
  display: flex;
  flex-direction: column;
  z-index: 5;
  overflow: hidden;
}

/* 左侧专属边框色，使用复古色 */
.left-sidebar {
  border-right: 2px solid #d3c4a1;
}

.sidebar-content {
  width: 100%;
  padding: 20px;
  min-width: 280px;
  height: 100%;
  box-sizing: border-box;
}

/* 收起时的状态 */
.sidebar.is-collapsed {
  width: 0 !important;
  min-width: 0 !important;
  border: none;
}

/* --- 内部清单复古排版 --- */
h2 {
  color: #5c4b37;
  font-size: 1.2rem;
  margin-top: 0;
  margin-bottom: 20px;
  border-bottom: 2px dashed #d3c4a1; /* 虚线分割线 */
  padding-bottom: 10px;
}

.todo-items {
  list-style: none;
  padding: 0;
  margin: 0;
}

.todo-items li {
  padding: 12px 0;
  color: #5c4b37;
  border-bottom: 1px solid #eaddc4;
  display: flex;
  align-items: center;
  font-size: 15px;
  cursor: pointer;
  transition: background-color 0.2s;
}

.todo-items li:hover {
  background-color: rgba(211, 196, 161, 0.2);
}

/* 用 CSS 画一个复古的方框作为 Checkbox */
.todo-items li::before {
  content: '☐';
  margin-right: 12px;
  font-size: 1.4rem;
  color: #b5a992;
  line-height: 1;
}

.todo-items li:hover::before {
  color: #5c4b37;
}
</style>