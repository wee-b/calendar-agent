<template>
  <div class="sidebar-chat">
    <div class="sidebar-chat-header">历史对话</div>

    <div class="sidebar-chat-list">
      <div v-if="!isUserLoggedIn" class="sidebar-chat-empty">
        登录后查看对话
      </div>
      <div v-else-if="sessions.length === 0" class="sidebar-chat-empty">
        暂无历史对话
      </div>
      <div
        v-for="s in sessions"
        :key="s.sessionId"
        class="sidebar-chat-item"
        :class="{ active: s.sessionId === currentSessionId }"
        @click="handleSelectSession(s)"
      >
        <span class="chat-bubble-icon"></span>
        <span class="sidebar-chat-title" :title="s.title">{{ s.title || '新对话' }}</span>
        <button class="sidebar-chat-pin" title="置顶">⌖</button>
        <button class="sidebar-chat-delete" @click.stop="promptDelete(s.sessionId)" title="删除">×</button>
      </div>
    </div>

    <div v-if="showDeleteConfirm" class="delete-overlay">
      <div class="delete-modal">
        <p>确定删除该对话？</p>
        <div class="delete-actions">
          <button class="del-btn danger" @click="executeDelete">删除</button>
          <button class="del-btn cancel" @click="showDeleteConfirm = false">取消</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { getSessionsAPI, deleteSessionAPI, type ChatSessionVO } from '../../../api/chat';
import { tokenRef } from '../../../utils/auth';

const router = useRouter();
const route = useRoute();
const isUserLoggedIn = computed(() => !!tokenRef.value);

const sessions = ref<ChatSessionVO[]>([]);
const currentSessionId = ref<string | null>(null);
const showDeleteConfirm = ref(false);
const pendingDeleteId = ref<string | null>(null);

const fetchSessions = async () => {
  if (!isUserLoggedIn.value) return;
  try {
    const res = await getSessionsAPI();
    sessions.value = res || [];
  } catch {}
};

const handleSelectSession = (session: ChatSessionVO) => {
  currentSessionId.value = session.sessionId;
  router.push({ path: '/conversation', query: { sessionId: session.sessionId } });
};

const syncCurrentSessionFromRoute = () => {
  const id = route.query.sessionId;
  currentSessionId.value = Array.isArray(id) ? id[0] || null : id || null;
};

const promptDelete = (sessionId: string) => {
  pendingDeleteId.value = sessionId;
  showDeleteConfirm.value = true;
};

const executeDelete = async () => {
  if (!pendingDeleteId.value) return;
  try {
    await deleteSessionAPI(pendingDeleteId.value);
    ElMessage.success('已删除对话');
    if (currentSessionId.value === pendingDeleteId.value) {
      currentSessionId.value = null;
    }
    await fetchSessions();
  } catch {}
  finally {
    showDeleteConfirm.value = false;
    pendingDeleteId.value = null;
  }
};

onMounted(() => {
  syncCurrentSessionFromRoute();
  fetchSessions();
});

watch(isUserLoggedIn, (newVal) => {
  if (newVal) fetchSessions();
  else {
    sessions.value = [];
    currentSessionId.value = null;
  }
});

watch(() => route.query.sessionId, syncCurrentSessionFromRoute);

defineExpose({ fetchSessions });
</script>

<style scoped>
.sidebar-chat {
  height: 100%;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  position: relative;
}

.sidebar-chat-header {
  flex-shrink: 0;
  padding: 0 26px 12px;
  color: #a1a1aa;
  font-size: 14px;
}

.sidebar-chat-list {
  flex: 1;
  overflow-y: auto;
  padding: 0 18px 12px;
}

.sidebar-chat-list::-webkit-scrollbar {
  width: 4px;
}

.sidebar-chat-list::-webkit-scrollbar-thumb {
  background: #d4d4d8;
  border-radius: 999px;
}

.sidebar-chat-empty {
  padding: 12px 8px;
  color: #a1a1aa;
  font-size: 13px;
}

.sidebar-chat-item {
  height: 44px;
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 0 8px;
  border-radius: 10px;
  color: #3f3f46;
  cursor: pointer;
  transition: background 0.16s;
}

.sidebar-chat-item:hover,
.sidebar-chat-item.active {
  background: #eeeeef;
}

.chat-bubble-icon {
  width: 22px;
  height: 22px;
  border: 1px solid #d4d4d8;
  border-radius: 50%;
  position: relative;
  flex-shrink: 0;
  background: #ffffff;
}

.chat-bubble-icon::before,
.chat-bubble-icon::after {
  content: "";
  position: absolute;
  top: 9px;
  width: 3px;
  height: 3px;
  border-radius: 50%;
  background: #a1a1aa;
}

.chat-bubble-icon::before {
  left: 6px;
  box-shadow: 5px 0 0 #a1a1aa;
}

.chat-bubble-icon::after {
  right: 5px;
}

.sidebar-chat-title {
  flex: 1;
  min-width: 0;
  color: #3f3f46;
  font-size: 15px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.sidebar-chat-pin,
.sidebar-chat-delete {
  width: 22px;
  height: 22px;
  display: grid;
  place-items: center;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: #a1a1aa;
  cursor: pointer;
  opacity: 0;
  transition: opacity 0.16s, background 0.16s, color 0.16s;
}

.sidebar-chat-item:hover .sidebar-chat-pin,
.sidebar-chat-item:hover .sidebar-chat-delete {
  opacity: 1;
}

.sidebar-chat-pin:hover,
.sidebar-chat-delete:hover {
  background: #ffffff;
  color: #18181b;
}

.delete-overlay {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(244, 244, 245, 0.72);
  backdrop-filter: blur(2px);
  z-index: 10;
}

.delete-modal {
  width: 250px;
  padding: 18px;
  background: #ffffff;
  border: 1px solid #dedee3;
  border-radius: 14px;
  box-shadow: 0 18px 48px rgba(15, 23, 42, 0.16);
}

.delete-modal p {
  margin: 0 0 16px;
  color: #18181b;
  font-size: 15px;
  font-weight: 650;
}

.delete-actions {
  display: flex;
  gap: 10px;
}

.del-btn {
  flex: 1;
  height: 36px;
  border: 0;
  border-radius: 9px;
  cursor: pointer;
  font-weight: 650;
}

.del-btn.cancel {
  background: #f4f4f5;
  color: #18181b;
}

.del-btn.danger {
  background: #ef4444;
  color: #ffffff;
}
</style>
