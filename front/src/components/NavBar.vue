<template>
  <header class="navbar">
    <div class="nav-left">
      <span class="status-dot"></span>
      <span class="status-text">Agent ready</span>
    </div>

    <div class="nav-center">
      <h1 class="logo">语音日历 Agent</h1>
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
            <span class="icon">U</span>
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
        <span class="icon">?</span>
      </button>
    </div>
  </header>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { ElMessage } from 'element-plus';
import { userInfoRef, tokenRef } from '../utils/auth';

defineEmits<{
  (e: 'logout'): void;
  (e: 'login'): void;
  (e: 'help'): void;
}>();

const isLoggedIn = computed(() => !!tokenRef.value);

const userName = computed(() => {
  return userInfoRef.value?.userName || '未知用户';
});

const handleProfile = () => {
  ElMessage({
    message: '个人中心功能待实现',
    type: 'info',
    duration: 2000
  });
};
</script>

<style scoped>
.navbar {
  height: 60px;
  background: rgba(255, 255, 255, 0.76);
  border: 1px solid rgba(15, 23, 42, 0.08);
  border-radius: 14px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 0 24px;
  box-shadow: 0 18px 50px rgba(15, 23, 42, 0.08);
  z-index: 10;
  color: #101828;
  flex-shrink: 0;
  margin-bottom: 14px;
  backdrop-filter: blur(18px);
}

.nav-left,
.nav-right {
  display: flex;
  align-items: center;
  gap: 12px;
  flex: 1;
}

.nav-right {
  justify-content: flex-end;
}

.nav-center {
  text-align: center;
}

.logo {
  font-size: 1.05rem;
  font-weight: 750;
  margin: 0;
  color: #101828;
}

.status-dot {
  width: 9px;
  height: 9px;
  border-radius: 999px;
  background: #10b981;
  box-shadow: 0 0 0 5px rgba(16, 185, 129, 0.12);
}

.status-text {
  color: #667085;
  font-size: 13px;
  font-weight: 650;
}

.icon-btn {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 16px;
  border: 1px solid rgba(15, 23, 42, 0.1);
  background: #ffffff;
  color: #344054;
  border-radius: 8px;
  font-size: 14px;
  font-weight: 650;
  cursor: pointer;
  transition: all 0.2s ease;
}

.icon-btn:hover {
  background: #f8fafc;
  border-color: rgba(37, 99, 235, 0.28);
  color: #1d4ed8;
}

.highlight-btn {
  background: linear-gradient(135deg, #2563eb, #0891b2);
  color: #ffffff;
  border: none;
  box-shadow: 0 10px 24px rgba(37, 99, 235, 0.2);
}

.highlight-btn:hover {
  background: linear-gradient(135deg, #1d4ed8, #0e7490);
  color: #ffffff;
}

.user-dropdown {
  position: relative;
  display: inline-block;
}

.dropdown-menu {
  display: none;
  position: absolute;
  top: 100%;
  right: 0;
  margin-top: 8px;
  background: #ffffff;
  border: 1px solid rgba(15, 23, 42, 0.1);
  border-radius: 8px;
  min-width: 130px;
  box-shadow: 0 18px 40px rgba(15, 23, 42, 0.14);
  z-index: 100;
  overflow: hidden;
}

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
  color: #344054;
  transition: background 0.2s;
  text-align: center;
  font-size: 14px;
}

.dropdown-item:hover {
  background: #f1f5f9;
  font-weight: bold;
}

.logout-text {
  color: #dc2626;
  border-top: 1px solid #eef2f7;
}

.logout-text:hover {
  color: white;
  background-color: #dc2626;
}

@keyframes fadeIn {
  from { opacity: 0; transform: translateY(-10px); }
  to { opacity: 1; transform: translateY(0); }
}
</style>
