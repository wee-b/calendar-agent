<template>
  <div class="app-layout">
    <aside class="app-sidebar" :class="{ 'is-hidden': !showHistorySidebar }">
      <div class="sidebar-search">
        <AppIcon name="search" class="search-icon" />
        <input
          ref="searchInputRef"
          v-model="sidebarKeyword"
          type="text"
          placeholder="搜索对话"
        />
        <kbd>Ctrl K</kbd>
      </div>

      <nav class="sidebar-nav">
        <button
          class="nav-btn"
          :class="{ active: isNewConversationRoute }"
          @click="router.push('/conversation')"
        >
          <AppIcon name="compose" />
          <span class="nav-label">新对话</span>
          <span class="nav-shortcut">Ctrl Shift K</span>
        </button>
        <button
          class="nav-btn"
          :class="{ active: route.path === '/calendar-view' }"
          @click="router.push('/calendar-view')"
        >
          <AppIcon name="calendar" />
          <span class="nav-label">待办日历</span>
        </button>
        <button
          class="nav-btn"
          :class="{ active: route.path === '/today' }"
          @click="router.push('/today')"
        >
          <AppIcon name="today" />
          <span class="nav-label">本日事项</span>
        </button>
      </nav>

      <div class="sidebar-bottom">
        <SidebarChatList ref="sidebarChatRef" :keyword="sidebarKeyword" />
      </div>

      <div class="sidebar-user" ref="userMenuRoot">
        <transition name="menu-pop">
          <div v-if="showUserMenu" class="user-popover">
            <button class="menu-action" @click="handleMenuAction('设置')">
              <AppIcon name="gear" />
              <span>设置</span>
            </button>
            <button class="menu-action" @click="handleMenuAction('升级到专业版')">
              <AppIcon name="sparkle" />
              <span>升级到专业版</span>
            </button>
            <button class="menu-action" @click="handleSwitchAccount">
              <AppIcon name="switch" />
              <span>切换账号</span>
            </button>
            <button class="menu-action danger" @click="handleLogout">
              <AppIcon name="logout" />
              <span>退出登录</span>
            </button>
          </div>
        </transition>

        <button class="user-trigger" @click="handleUserTrigger">
          <span class="user-avatar">{{ userInitial }}</span>
          <span class="user-name">{{ userName }}</span>
          <AppIcon v-if="hasToken" name="chevron" class="user-chevron" />
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
import AppIcon from './components/AppIcon.vue';

const router = useRouter();
const route = useRoute();
const hasToken = computed(() => !!tokenRef.value);
const showAuthModal = ref(!tokenRef.value);
const showHelp = ref(false);
const showUserMenu = ref(false);
const showHistorySidebar = ref(true);
const isTodoExpanded = ref(false);
const sidebarKeyword = ref('');
const userMenuRoot = ref<HTMLElement | null>(null);
const searchInputRef = ref<HTMLInputElement | null>(null);
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

const handleShortcut = (event: KeyboardEvent) => {
  if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k' && !event.shiftKey) {
    event.preventDefault();
    if (!showHistorySidebar.value) showHistorySidebar.value = true;
    searchInputRef.value?.focus();
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
  document.addEventListener('keydown', handleShortcut);
});

onUnmounted(() => {
  document.removeEventListener('click', handleOutsideClick);
  document.removeEventListener('keydown', handleShortcut);
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
  width: 272px;
  min-width: 272px;
  height: 100vh;
  display: flex;
  flex-direction: column;
  background: #f7f7f8;
  border-right: 1px solid #ececef;
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
  height: 38px;
  margin: 12px 12px 8px;
  padding: 0 10px 0 12px;
  display: flex;
  align-items: center;
  gap: 8px;
  border: 1px solid #e4e4e7;
  border-radius: 10px;
  background: #ffffff;
  color: #a1a1aa;
}

.sidebar-search:focus-within {
  border-color: #d4d4d8;
  box-shadow: 0 0 0 3px rgba(24, 24, 27, 0.04);
}

.sidebar-search input {
  flex: 1;
  min-width: 0;
  border: 0;
  outline: 0;
  background: transparent;
  color: #18181b;
  font-size: 13px;
}

.sidebar-search input::placeholder {
  color: #a1a1aa;
}

.sidebar-search kbd {
  height: 20px;
  padding: 0 6px;
  display: grid;
  place-items: center;
  border: 1px solid #ececef;
  border-radius: 6px;
  background: #f7f7f8;
  color: #a1a1aa;
  font-size: 11px;
  font-family: inherit;
  white-space: nowrap;
}

.search-icon {
  color: #a1a1aa;
}

.mini-avatar,
.user-avatar {
  width: 28px;
  height: 28px;
  display: grid;
  place-items: center;
  border-radius: 50%;
  background: #18181b;
  color: #ffffff;
  font-size: 12px;
  font-weight: 650;
}

.sidebar-nav {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 4px 10px 0;
}

.nav-btn {
  width: 100%;
  height: 38px;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 10px;
  border: 0;
  border-radius: 10px;
  background: transparent;
  color: #3f3f46;
  cursor: pointer;
  font-size: 14px;
  text-align: left;
  transition: background 0.16s, color 0.16s;
}

.nav-btn:hover {
  background: #ececef;
  color: #18181b;
}

.nav-btn.active {
  background: #e4e4e7;
  color: #18181b;
  font-weight: 600;
}

.nav-label {
  flex: 1;
}

.nav-shortcut {
  color: #a1a1aa;
  font-size: 11px;
  opacity: 0;
  transition: opacity 0.16s;
}

.nav-btn:hover .nav-shortcut,
.nav-btn.active .nav-shortcut {
  opacity: 1;
}

.sidebar-bottom {
  flex: 1;
  min-height: 0;
  overflow: hidden;
  margin-top: 16px;
}

.sidebar-user {
  position: relative;
  flex-shrink: 0;
  padding: 8px 10px 10px;
  border-top: 1px solid #ececef;
}

.user-trigger {
  width: 100%;
  height: 44px;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 8px;
  border: 0;
  border-radius: 10px;
  background: transparent;
  cursor: pointer;
  color: #18181b;
  font-size: 14px;
  font-weight: 550;
  text-align: left;
}

.user-trigger:hover {
  background: #ececef;
}

.user-avatar {
  width: 28px;
  height: 28px;
  font-size: 12px;
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
  transform: rotate(90deg);
}

.user-popover {
  position: absolute;
  left: 10px;
  right: 10px;
  bottom: 62px;
  width: auto;
  padding: 8px;
  background: #ffffff;
  border: 1px solid #ececef;
  border-radius: 12px;
  box-shadow: 0 12px 36px rgba(24, 24, 27, 0.12);
  z-index: 20;
}

.menu-action {
  width: 100%;
  height: 40px;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 10px;
  border: 0;
  background: transparent;
  color: #3f3f46;
  border-radius: 8px;
  cursor: pointer;
  font-size: 14px;
  text-align: left;
}

.menu-action:hover {
  background: #f4f4f5;
  color: #18181b;
}

.menu-action + .menu-action {
  margin-top: 2px;
}

.menu-action.danger {
  color: #dc2626;
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
    width: 272px;
    min-width: 272px;
  }

  .user-popover {
    width: auto;
  }
}
</style>
