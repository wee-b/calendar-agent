<template>
  <div class="sidebar-chat">
    <div class="sidebar-chat-header">历史对话</div>

    <div class="sidebar-chat-list">
      <div v-if="!isUserLoggedIn" class="sidebar-chat-empty">
        登录后查看对话
      </div>
      <div v-else-if="visibleSessions.length === 0" class="sidebar-chat-empty">
        {{ keyword?.trim() ? '没有匹配的对话' : '暂无历史对话' }}
      </div>
      <div
        v-for="s in visibleSessions"
        :key="s.sessionId"
        class="sidebar-chat-item"
        :class="{ active: s.sessionId === currentSessionId }"
        @click="handleSelectSession(s)"
      >
        <span class="sidebar-chat-title" :title="s.title">{{ s.title || '新对话' }}</span>
        <button class="sidebar-chat-delete" @click.stop="promptDelete(s.sessionId)" title="删除">删除</button>
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
const props = defineProps<{
  keyword?: string;
}>();

const sessions = ref<ChatSessionVO[]>([]);
const currentSessionId = ref<string | null>(null);
const showDeleteConfirm = ref(false);
const pendingDeleteId = ref<string | null>(null);

const visibleSessions = computed(() => {
  const keyword = props.keyword?.trim();
  if (!keyword) return sessions.value;
  return sessions.value.filter(session => (session.title || '新对话').includes(keyword));
});

const sortSessionsByRecent = (list: ChatSessionVO[]) => {
  return [...list].sort((a, b) => new Date(b.createTime).getTime() - new Date(a.createTime).getTime());
};

const fetchSessions = async () => {
  if (!isUserLoggedIn.value) return;
  try {
    const res = await getSessionsAPI();
    sessions.value = sortSessionsByRecent(res || []);
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
  padding: 8px 20px 8px;
  color: #a1a1aa;
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.04em;
}

.sidebar-chat-list {
  flex: 1;
  overflow-y: auto;
  padding: 0 10px 8px;
  scrollbar-width: thin;
  scrollbar-color: #d4d4d8 transparent;
}

.sidebar-chat-list::-webkit-scrollbar {
  width: 6px;
}

.sidebar-chat-list::-webkit-scrollbar-thumb {
  background: #d4d4d8;
  border-radius: 999px;
}

.sidebar-chat-empty {
  padding: 16px 10px;
  color: #a1a1aa;
  font-size: 13px;
  line-height: 1.5;
}

.sidebar-chat-item {
  min-height: 36px;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 10px;
  border-radius: 8px;
  color: #3f3f46;
  cursor: pointer;
  transition: background 0.16s, color 0.16s;
}

.sidebar-chat-item:hover {
  background: #ececef;
}

.sidebar-chat-item.active {
  background: #e4e4e7;
  color: #18181b;
}

.sidebar-chat-item.active .sidebar-chat-title {
  color: #18181b;
  font-weight: 600;
}

.sidebar-chat-title {
  flex: 1;
  min-width: 0;
  color: #3f3f46;
  font-size: 13px;
  line-height: 1.4;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.sidebar-chat-delete {
  height: 22px;
  padding: 0 6px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: #a1a1aa;
  cursor: pointer;
  font-size: 12px;
  opacity: 0;
  transition: opacity 0.16s, background 0.16s, color 0.16s;
}

.sidebar-chat-item:hover .sidebar-chat-delete,
.sidebar-chat-item.active .sidebar-chat-delete {
  opacity: 1;
}

.sidebar-chat-delete:hover {
  background: #ffffff;
  color: #dc2626;
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
