<template>
  <div class="app-layout">
    <aside class="app-sidebar" :class="{ 'is-hidden': !showHistorySidebar }">
      <div class="sidebar-search">
        <span class="search-icon"></span>
        <input type="text" placeholder="搜索..." />
        <kbd>Ctrl K</kbd>
      </div>

      <button class="profile-mini" @click="handleProfileEntry">
        <span class="mini-avatar">{{ userInitial }}</span>
        <span>{{ userName }}</span>
      </button>

      <nav class="sidebar-nav">
        <button
          class="nav-btn"
          :class="{ active: isNewConversationRoute }"
          @click="router.push('/conversation')"
        >
          <span class="nav-icon compose-icon"></span>
          <span class="nav-label">新对话</span>
          <span class="nav-shortcut">Ctrl Shift K</span>
        </button>
        <button
          class="nav-btn"
          :class="{ active: route.path === '/calendar-view' }"
          @click="router.push('/calendar-view')"
        >
          <span class="nav-icon calendar-icon"></span>
          <span class="nav-label">待办日历</span>
        </button>
        <button
          class="nav-btn"
          :class="{ active: route.path === '/today' }"
          @click="router.push('/today')"
        >
          <span class="nav-icon today-icon"></span>
          <span class="nav-label">本日事项</span>
        </button>
      </nav>

      <div class="sidebar-bottom">
        <SidebarChatList ref="sidebarChatRef" />
      </div>

      <div class="sidebar-user" ref="userMenuRoot">
        <transition name="menu-pop">
          <div v-if="showUserMenu" class="user-popover">
            <button class="menu-action" @click="handleMenuAction('设置')">
              <span class="menu-icon gear-icon"></span>
              <span>设置</span>
            </button>
            <button class="menu-action" @click="handleMenuAction('升级到专业版')">
              <span class="menu-icon sparkle-icon"></span>
              <span>升级到专业版</span>
            </button>
            <button class="menu-action" @click="handleSwitchAccount">
              <span class="menu-icon switch-icon"></span>
              <span>切换账号</span>
            </button>
            <button class="menu-action danger" @click="handleLogout">
              <span class="menu-icon logout-icon"></span>
              <span>退出登录</span>
            </button>
          </div>
        </transition>

        <button class="user-trigger" @click="handleUserTrigger">
          <span class="user-avatar">{{ userInitial }}</span>
          <span class="user-name">{{ userName }}</span>
          <span class="user-chevron" v-if="hasToken">›</span>
        </button>
      </div>
    </aside>

    <div class="app-main">
      <LayoutNavBar
        :title="navTitle"
        :is-sidebar-open="showHistorySidebar"
        :show-todo-toggle="route.path === '/calendar-view'"
        :is-todo-expanded="isTodoExpanded"
        @toggle-sidebar="showHistorySidebar = !showHistorySidebar"
        @new-chat="router.push('/conversation')"
        @toggle-todo="isTodoExpanded = !isTodoExpanded"
      />
      <main class="app-content">
        <router-view
          :is-todo-expanded="isTodoExpanded"
          @refresh="handleRefresh"
          @title-change="handleChatTitleChange"
        />
      </main>
    </div>

    <AuthModal v-if="!hasToken && showAuthModal" @success="handleLoginSuccess" @close="showAuthModal = false" />
    <Help :visible="showHelp" @close="showHelp = false" />
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch } from 'vue';
import { useRouter, useRoute } from 'vue-router';
import { ElMessage } from 'element-plus';
import { tokenRef, clearAuth, userInfoRef } from '../../utils/auth';
import { logoutAPI } from '../../api/user';

import AuthModal from '../../components/AuthModal.vue';
import Help from '../../components/Help.vue';
import SidebarChatList from './components/SidebarChatList.vue';
import LayoutNavBar from './components/LayoutNavBar.vue';

const router = useRouter();
const route = useRoute();
const hasToken = computed(() => !!tokenRef.value);
const showAuthModal = ref(!tokenRef.value);
const showHelp = ref(false);
const showUserMenu = ref(false);
const showHistorySidebar = ref(true);
const isTodoExpanded = ref(false);
const userMenuRoot = ref<HTMLElement | null>(null);
const sidebarChatRef = ref<InstanceType<typeof SidebarChatList> | null>(null);
const isNewConversationRoute = computed(() => route.path === '/conversation' && !route.query.sessionId);
const currentChatTitle = ref('新对话');

const userName = computed(() => {
  if (!hasToken.value) return '去登录';
  return userInfoRef.value?.userName || userInfoRef.value?.userCode || '用户';
});
const userInitial = computed(() => userName.value.slice(0, 1).toUpperCase());
const navTitle = computed(() => {
  if (route.path === '/calendar-view') return '日历';
  if (route.path === '/today') return '本日事项';
  if (!hasToken.value) return '语音助手';
  return route.query.sessionId ? currentChatTitle.value : '新对话';
});

const handleLoginSuccess = () => {
  showAuthModal.value = false;
};

const handleRefresh = () => {
  sidebarChatRef.value?.fetchSessions();
};

const handleChatTitleChange = (title: string) => {
  currentChatTitle.value = title || '新对话';
};

const handleProfileEntry = () => {
  if (!hasToken.value) {
    showAuthModal.value = true;
    return;
  }
  router.push('/conversation');
};

const handleUserTrigger = () => {
  if (!hasToken.value) {
    showAuthModal.value = true;
    return;
  }
  showUserMenu.value = !showUserMenu.value;
};

const handleMenuAction = (label: string) => {
  showUserMenu.value = false;
  ElMessage.info(`${label}功能待实现`);
};

const handleSwitchAccount = () => {
  showUserMenu.value = false;
  showAuthModal.value = true;
};

const handleLogout = async () => {
  showUserMenu.value = false;
  try { await logoutAPI(); } catch {}
  clearAuth();
  showAuthModal.value = true;
  ElMessage.success('已退出登录');
};

const handleOutsideClick = (event: MouseEvent) => {
  if (!userMenuRoot.value?.contains(event.target as Node)) {
    showUserMenu.value = false;
  }
};

watch(
  () => [route.path, route.query.sessionId],
  () => {
    if (route.path !== '/conversation' || !route.query.sessionId) {
      currentChatTitle.value = '新对话';
    }
  }
);

onMounted(() => {
  document.addEventListener('click', handleOutsideClick);
});

onUnmounted(() => {
  document.removeEventListener('click', handleOutsideClick);
});
</script>

<style scoped>
.app-layout {
  width: 100vw;
  height: 100vh;
  display: flex;
  overflow: hidden;
  background: #ffffff;
  color: #111827;
  position: relative;
}

.app-sidebar {
  width: 292px;
  min-width: 292px;
  height: 100vh;
  display: flex;
  flex-direction: column;
  background: #f7f7f8;
  border-right: 1px solid #e8e8eb;
  position: relative;
  overflow: hidden;
  transition: width 0.22s ease, min-width 0.22s ease, border-color 0.22s ease;
}

.app-sidebar.is-hidden {
  width: 0;
  min-width: 0;
  border-right-color: transparent;
}

.app-sidebar.is-hidden > * {
  opacity: 0;
  pointer-events: none;
}

.sidebar-search {
  height: 44px;
  margin: 14px 14px 10px;
  padding: 0 12px;
  display: flex;
  align-items: center;
  gap: 10px;
  border: 1px solid #dedee3;
  border-radius: 12px;
  background: #f0f0f2;
  color: #a1a1aa;
}

.sidebar-search input {
  flex: 1;
  min-width: 0;
  border: 0;
  outline: 0;
  background: transparent;
  color: #111827;
  font-size: 15px;
}

.sidebar-search input::placeholder {
  color: #a1a1aa;
}

.sidebar-search kbd {
  border: 0;
  background: transparent;
  color: #9ca3af;
  font-size: 14px;
  font-family: inherit;
}

.search-icon,
.nav-icon,
.menu-icon {
  width: 20px;
  height: 20px;
  display: inline-block;
  position: relative;
  flex-shrink: 0;
  color: currentColor;
}

.search-icon::before {
  content: "";
  position: absolute;
  width: 11px;
  height: 11px;
  border: 2px solid currentColor;
  border-radius: 50%;
  left: 1px;
  top: 1px;
}

.search-icon::after {
  content: "";
  position: absolute;
  width: 8px;
  height: 2px;
  background: currentColor;
  border-radius: 2px;
  transform: rotate(45deg);
  right: 2px;
  bottom: 3px;
}

.profile-mini {
  margin: 2px 14px 10px;
  padding: 8px 12px;
  display: flex;
  align-items: center;
  gap: 10px;
  border: 0;
  background: transparent;
  color: #18181b;
  border-radius: 10px;
  cursor: pointer;
  font-size: 15px;
  text-align: left;
}

.profile-mini:hover {
  background: #eeeeef;
}

.mini-avatar,
.user-avatar {
  width: 24px;
  height: 24px;
  display: grid;
  place-items: center;
  border-radius: 50%;
  background: #dbeafe;
  color: #2563eb;
  font-size: 13px;
  font-weight: 700;
}

.sidebar-nav {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 0 14px;
}

.nav-btn {
  width: 100%;
  height: 44px;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 0 12px;
  border: 0;
  border-radius: 12px;
  background: transparent;
  color: #18181b;
  cursor: pointer;
  font-size: 16px;
  text-align: left;
  transition: background 0.16s, box-shadow 0.16s;
}

.nav-btn:hover,
.nav-btn.active {
  background: #ffffff;
  box-shadow: 0 8px 22px rgba(15, 23, 42, 0.08);
}

.nav-label {
  flex: 1;
}

.nav-shortcut {
  color: #a1a1aa;
  font-size: 14px;
}

.compose-icon::before {
  content: "";
  position: absolute;
  inset: 3px;
  border: 2px solid currentColor;
  border-radius: 6px;
}

.compose-icon::after {
  content: "";
  position: absolute;
  width: 10px;
  height: 2px;
  left: 7px;
  top: 9px;
  background: currentColor;
  transform: rotate(-38deg);
  border-radius: 2px;
}

.calendar-icon::before {
  content: "";
  position: absolute;
  inset: 3px 2px 2px;
  border: 2px solid currentColor;
  border-radius: 5px;
}

.calendar-icon::after {
  content: "";
  position: absolute;
  left: 5px;
  right: 5px;
  top: 8px;
  height: 2px;
  background: currentColor;
}

.today-icon::before {
  content: "";
  position: absolute;
  inset: 3px;
  border: 2px solid currentColor;
  border-radius: 50%;
}

.today-icon::after {
  content: "";
  position: absolute;
  width: 7px;
  height: 7px;
  left: 7px;
  top: 7px;
  border-radius: 50%;
  background: currentColor;
  box-shadow: 0 7px 0 -2px currentColor;
}

.sidebar-bottom {
  flex: 1;
  min-height: 0;
  overflow: hidden;
  margin-top: 28px;
}

.sidebar-user {
  position: relative;
  flex-shrink: 0;
  padding: 10px 14px 14px;
  border-top: 1px solid #e8e8eb;
}

.user-trigger {
  width: 100%;
  height: 54px;
  display: flex;
  align-items: center;
  gap: 12px;
  border: 0;
  border-radius: 14px;
  background: transparent;
  cursor: pointer;
  color: #111827;
  font-size: 16px;
  text-align: left;
}

.user-trigger:hover {
  background: #eeeeef;
}

.user-avatar {
  width: 42px;
  height: 42px;
  font-size: 16px;
}

.user-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.user-chevron {
  color: #a1a1aa;
  font-size: 24px;
}

.user-popover {
  position: absolute;
  left: 24px;
  bottom: 78px;
  width: 252px;
  padding: 14px 18px;
  background: #ffffff;
  border: 1px solid #dedee3;
  border-radius: 14px;
  box-shadow: 0 18px 48px rgba(15, 23, 42, 0.16);
  z-index: 20;
}

.menu-action {
  width: 100%;
  height: 50px;
  display: flex;
  align-items: center;
  gap: 14px;
  border: 0;
  background: transparent;
  color: #111827;
  border-radius: 10px;
  cursor: pointer;
  font-size: 17px;
  text-align: left;
}

.menu-action:hover {
  background: #f4f4f5;
}

.menu-action + .menu-action {
  margin-top: 6px;
}

.menu-action.danger {
  color: #111827;
}

.gear-icon::before {
  content: "";
  position: absolute;
  inset: 3px;
  border: 2px solid currentColor;
  border-radius: 50%;
}

.gear-icon::after {
  content: "";
  position: absolute;
  width: 6px;
  height: 6px;
  left: 7px;
  top: 7px;
  border: 2px solid currentColor;
  border-radius: 50%;
  background: #ffffff;
}

.sparkle-icon::before {
  content: "*";
  position: absolute;
  inset: 0;
  display: grid;
  place-items: center;
  font-size: 24px;
}

.switch-icon::before {
  content: "";
  position: absolute;
  left: 2px;
  right: 2px;
  top: 5px;
  height: 2px;
  background: currentColor;
  box-shadow: 0 8px 0 currentColor;
}

.switch-icon::after {
  content: "";
  position: absolute;
  right: 1px;
  top: 2px;
  width: 7px;
  height: 7px;
  border-top: 2px solid currentColor;
  border-right: 2px solid currentColor;
  transform: rotate(45deg);
}

.logout-icon::before {
  content: "";
  position: absolute;
  left: 3px;
  top: 4px;
  width: 10px;
  height: 12px;
  border: 2px solid currentColor;
  border-right: 0;
  border-radius: 4px 0 0 4px;
}

.logout-icon::after {
  content: ">";
  position: absolute;
  right: 0;
  top: -2px;
  font-size: 20px;
}

.app-main {
  flex: 1;
  min-width: 0;
  height: 100vh;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: #ffffff;
}

.app-content {
  flex: 1;
  min-height: 0;
  display: flex;
  overflow: hidden;
  background: #ffffff;
}

.menu-pop-enter-active,
.menu-pop-leave-active {
  transition: opacity 0.14s ease, transform 0.14s ease;
}

.menu-pop-enter-from,
.menu-pop-leave-to {
  opacity: 0;
  transform: translateY(8px);
}

@media (max-width: 760px) {
  .app-sidebar {
    width: 292px;
    min-width: 292px;
  }

  .user-popover {
    width: 244px;
  }
}
</style>
