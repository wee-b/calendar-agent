<template>
  <div class="chat-history" :class="{ 'has-messages': messages.length > 0 }" ref="chatHistoryRef">
    <EmptyChat v-if="messages.length === 0" @select-prompt="$emit('select-prompt', $event)" />

    <div
      v-for="(msg, index) in messages"
      :key="index"
      class="msg-bubble"
      :class="isUserRole(msg.role) ? 'user-msg' : 'ai-msg'"
    >
      <span class="avatar">{{ isUserRole(msg.role) ? '我' : 'AI' }}</span>

      <div class="bubble-content">
        <div v-if="msg.thinking?.length" class="thinking-panel" :class="{ completed: msg.thinkingDone }">
          <button class="thinking-summary" @click="$emit('toggle-thinking', index)">
            <span>{{ thinkingSummary(msg) }}</span>
            <span class="thinking-arrow" :class="{ open: !msg.thinkingCollapsed }">⌄</span>
          </button>
          <div v-if="!msg.thinkingCollapsed" class="thinking-steps">
            <div
              v-for="(step, stepIndex) in msg.thinking"
              :key="`${index}-${stepIndex}`"
              class="thinking-step"
              :class="{ done: isThinkingStepDone(step), failed: isThinkingStepFailed(step) }"
            >
              {{ step }}
            </div>
          </div>
        </div>
        <div v-else-if="!isUserRole(msg.role) && msg.responseTimeMs != null" class="response-time">
          耗时 {{ formatResponseTime(msg.responseTimeMs) }}
        </div>

        <p v-if="msg.content && isUserRole(msg.role)">{{ msg.content }}</p>
        <div
          v-else-if="msg.content"
          class="markdown-body"
          v-html="renderMarkdown(msg.content)"
        ></div>
        <TypingIndicator v-else-if="msg.loading" />

        <div class="msg-actions" v-if="!msg.loading && !isUserRole(msg.role)">
          <span class="action-icon" @click="$emit('copy', msg.content)">复制</span>
          <span
            class="action-icon"
            :class="{ active: readingMsgIndex === index && !isPaused, paused: readingMsgIndex === index && isPaused }"
            @click="$emit('read', msg.content, index)"
          >{{ readingMsgIndex === index ? (isPaused ? '继续' : '暂停') : '朗读' }}</span>
          <span class="action-icon danger" @click="$emit('delete-last-round')">撤回</span>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { nextTick, ref } from 'vue';
import { renderMarkdown } from '../../../utils/markdown';
import EmptyChat from './EmptyChat.vue';
import TypingIndicator from './TypingIndicator.vue';
import type { ChatMessage } from './types';

defineProps<{
  messages: ChatMessage[];
  readingMsgIndex: number | null;
  isPaused: boolean;
  isUserRole: (role: string) => boolean;
  thinkingSummary: (message: ChatMessage) => string;
  isThinkingStepDone: (step: string) => boolean;
  isThinkingStepFailed: (step: string) => boolean;
  formatResponseTime: (durationMs: number) => string;
}>();

defineEmits<{
  (e: 'select-prompt', prompt: string): void;
  (e: 'toggle-thinking', index: number): void;
  (e: 'copy', content: string): void;
  (e: 'read', content: string, index: number): void;
  (e: 'delete-last-round'): void;
}>();

const chatHistoryRef = ref<HTMLElement | null>(null);

const scrollToBottom = async () => {
  await nextTick();
  if (chatHistoryRef.value) {
    chatHistoryRef.value.scrollTop = chatHistoryRef.value.scrollHeight;
  }
};

defineExpose({ scrollToBottom });
</script>
