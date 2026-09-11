<template>
  <div class="session-dropdown">
    <div
      v-for="session in sessions"
      :key="session.sessionId"
      class="session-item"
      :class="{ active: session.sessionId === currentSessionId }"
      @click="$emit('select', session)"
    >
      <span class="session-name" :title="session.title">{{ session.title || '新对话' }}</span>
      <button class="delete-btn" @click.stop="$emit('delete', session.sessionId)" title="删除对话">×</button>
    </div>
    <div v-if="sessions.length === 0" class="empty-sessions">暂无历史对话</div>
  </div>
</template>

<script setup lang="ts">
import type { ChatSessionVO } from '../../../api/chat';

defineProps<{
  sessions: ChatSessionVO[];
  currentSessionId: string | null;
}>();

defineEmits<{
  (e: 'select', session: ChatSessionVO): void;
  (e: 'delete', sessionId: string): void;
}>();
</script>
