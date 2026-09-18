<template>
  <div class="chat-history" :class="{ 'has-messages': messages.length > 0 }" ref="chatHistoryRef">
    <EmptyChat v-if="messages.length === 0" @select-prompt="$emit('select-prompt', $event)" />

    <article
      v-for="(msg, index) in messages"
      :key="index"
      class="msg-item"
      :class="isUserRole(msg.role) ? 'is-user' : 'is-ai'"
    >
      <div class="bubble-content">
        <div v-if="msg.thinking?.length" class="thinking-panel" :class="{ completed: msg.thinkingDone }">
          <button class="thinking-summary" @click="$emit('toggle-thinking', index)">
            <span>{{ thinkingSummary(msg) }}</span>
            <svg
              class="thinking-arrow"
              :class="{ open: !msg.thinkingCollapsed }"
              viewBox="0 0 24 24"
              fill="none"
              aria-hidden="true"
            >
              <path d="M6 9l6 6 6-6" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" />
            </svg>
          </button>
          <div v-if="!msg.thinkingCollapsed" class="thinking-steps">
            <div
              v-for="(step, stepIndex) in msg.thinking"
              :key="`${index}-${stepIndex}`"
              class="thinking-step"
              :class="{ done: isThinkingStepDone(step), failed: isThinkingStepFailed(step) }"
            >
              <svg
                v-if="isThinkingStepDone(step)"
                class="step-mark"
                viewBox="0 0 24 24"
                fill="none"
                aria-hidden="true"
              >
                <path d="M5 12.5 10 17.5 19 7" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" />
              </svg>
              <svg
                v-else-if="isThinkingStepFailed(step)"
                class="step-mark is-failed"
                viewBox="0 0 24 24"
                fill="none"
                aria-hidden="true"
              >
                <path d="M8 8l8 8M16 8l-8 8" stroke="currentColor" stroke-width="2" stroke-linecap="round" />
              </svg>
              <span v-else class="step-dot"></span>
              <span class="step-text">{{ step }}</span>
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
          <button type="button" class="action-icon" @click="$emit('copy', msg.content)">复制</button>
          <button
            type="button"
            class="action-icon"
            :class="{ active: readingMsgIndex === index && !isPaused, paused: readingMsgIndex === index && isPaused }"
            @click="$emit('read', msg.content, index)"
          >{{ readingMsgIndex === index ? (isPaused ? '继续' : '暂停') : '朗读' }}</button>
          <button type="button" class="action-icon danger" @click="$emit('delete-last-round')">撤回</button>
        </div>
      </div>
    </article>
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

<style scoped>
.chat-history {
  flex: 1 1 0;
  min-width: 0;
  min-height: 0;
  overflow-x: hidden;
  overflow-y: auto;
  overscroll-behavior: contain;
  scrollbar-gutter: stable;
  scrollbar-width: thin;
  scrollbar-color: #d4d4d8 transparent;
}

.chat-history.has-messages {
  gap: 28px;
  padding: 28px 24px 248px;
  overflow-y: scroll;
}

.chat-history::-webkit-scrollbar {
  width: 8px;
}

.chat-history::-webkit-scrollbar-track {
  background: transparent;
}

.chat-history::-webkit-scrollbar-thumb {
  background: #d4d4d8;
  border-radius: 999px;
}

.msg-item {
  width: min(800px, 100%);
  margin: 0 auto;
  min-width: 0;
}

.bubble-content,
.markdown-body {
  max-width: 100%;
  min-width: 0;
}

.msg-item.is-ai .bubble-content {
  padding: 0;
  background: transparent;
  border: 0;
  box-shadow: none;
}

.msg-item.is-user {
  display: flex;
  justify-content: flex-end;
}

.msg-item.is-user .bubble-content {
  max-width: min(640px, 86%);
  padding: 10px 16px;
  border: 0;
  border-radius: 20px;
  background: #f4f4f5;
  box-shadow: none;
}

.msg-item.is-user .bubble-content p {
  color: #18181b;
  font-size: 15px;
  line-height: 1.65;
}

.response-time {
  margin-bottom: 8px;
  color: #a1a1aa;
  font-size: 12px;
}

.markdown-body {
  color: #18181b;
  font-size: 15px;
  line-height: 1.75;
}

.markdown-body :deep(p),
.markdown-body :deep(ul),
.markdown-body :deep(ol),
.markdown-body :deep(pre),
.markdown-body :deep(blockquote),
.markdown-body :deep(table) {
  margin: 0 0 12px;
}

.markdown-body :deep(p:last-child),
.markdown-body :deep(ul:last-child),
.markdown-body :deep(ol:last-child),
.markdown-body :deep(table:last-child) {
  margin-bottom: 0;
}

.markdown-body :deep(li + li) {
  margin-top: 6px;
}

.markdown-body :deep(table) {
  display: table;
  width: 100%;
  table-layout: fixed;
  border-collapse: separate;
  border-spacing: 0;
  overflow: hidden;
  border: 1px solid #ececef;
  border-radius: 12px;
  background: #ffffff;
}

.markdown-body :deep(th),
.markdown-body :deep(td) {
  min-width: 0;
  padding: 12px 14px;
  border: 0;
  border-bottom: 1px solid #ececef;
  text-align: left;
  vertical-align: top;
  word-break: break-word;
  overflow-wrap: anywhere;
}

.markdown-body :deep(th) {
  background: #f7f7f8;
  color: #52525b;
  font-size: 13px;
  font-weight: 650;
}

.markdown-body :deep(td) {
  color: #3f3f46;
  font-size: 14px;
  line-height: 1.65;
  background: #ffffff;
}

.markdown-body :deep(tr:last-child td) {
  border-bottom: 0;
}

.markdown-body :deep(th:nth-child(1)),
.markdown-body :deep(td:nth-child(1)) {
  width: 26%;
}

.markdown-body :deep(th:nth-child(2)),
.markdown-body :deep(td:nth-child(2)) {
  width: 118px;
  white-space: nowrap;
}

.markdown-body :deep(th:nth-child(3)),
.markdown-body :deep(td:nth-child(3)) {
  width: auto;
}

.msg-actions {
  gap: 8px;
  margin-top: 14px;
  padding-top: 10px;
  border-top: 1px solid #f4f4f5;
}

.action-icon {
  height: 28px;
  padding: 0 8px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: #71717a;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
}

.action-icon:hover,
.action-icon.active {
  background: #f4f4f5;
  color: #18181b;
  text-decoration: none;
}

.action-icon.paused,
.action-icon.danger:hover {
  color: #dc2626;
  background: #fef2f2;
}

.thinking-summary {
  color: #a1a1aa;
  font-size: 13px;
}

.thinking-arrow {
  width: 14px;
  height: 14px;
  display: block;
  color: #a1a1aa;
  transform: rotate(-90deg);
  transition: transform 0.16s ease;
}

.thinking-arrow.open {
  transform: rotate(0deg);
}

.thinking-steps {
  margin-top: 8px;
  padding-top: 10px;
  border-top: 1px solid #f4f4f5;
}

.thinking-step {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding-left: 0;
  color: #71717a;
  font-size: 13px;
  line-height: 1.6;
}

.thinking-step + .thinking-step {
  margin-top: 6px;
}

.thinking-step::before,
.thinking-step:not(.done)::before,
.thinking-step.done::before,
.thinking-step.failed::before {
  content: none;
  display: none;
}

.step-mark,
.step-dot {
  width: 14px;
  height: 14px;
  margin-top: 3px;
  flex-shrink: 0;
}

.step-mark {
  color: #22c55e;
}

.step-mark.is-failed {
  color: #ef4444;
}

.step-dot {
  border-radius: 50%;
  background: #d4d4d8;
}

.thinking-step.done .step-text {
  color: #52525b;
}

@media (max-width: 960px) {
  .chat-history.has-messages {
    padding: 20px 16px 200px;
    gap: 22px;
  }

  .markdown-body :deep(th:nth-child(1)),
  .markdown-body :deep(td:nth-child(1)),
  .markdown-body :deep(th:nth-child(2)),
  .markdown-body :deep(td:nth-child(2)) {
    width: auto;
    white-space: normal;
  }
}
</style>
