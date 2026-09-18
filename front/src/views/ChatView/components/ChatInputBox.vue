<template>
  <div class="chat-input-area" :class="{ 'is-expanded': isExpanded }">
    <div class="textarea-wrapper">
      <textarea
        :value="modelValue"
        class="chat-input"
        :placeholder="placeholder"
        @input="handleInput"
        @keydown.enter.exact.prevent="$emit('send')"
        :disabled="isSending || !isUserLoggedIn"
        :rows="isExpanded ? 10 : 3"
      ></textarea>
      <div class="input-actions">
        <div class="voice-controls" :class="{ 'is-voice-mode': inputMode === 2 }">
          <button
            class="voice-record-btn"
            :class="{ recording: isRecording }"
            @click="$emit('toggle-voice')"
            :disabled="isSending || !isUserLoggedIn"
          >
            {{ inputMode === 2 ? '听' : '语' }}
          </button>
          <div class="decibel-meter">
            <div
              class="db-segment"
              v-for="i in 10"
              :key="i"
              :style="{ height: dbBars[i - 1] + 'px' }"
            ></div>
          </div>
        </div>
        <button
          class="send-btn"
          @click="$emit('send')"
          :disabled="isSending || !modelValue.trim() || !isUserLoggedIn"
        >
          发送
        </button>
      </div>
      <span class="expand-icon" @click="$emit('toggle-expanded')" :title="isExpanded ? '收起' : '展开'">
        {{ isExpanded ? '收起' : '展开' }}
      </span>
    </div>
    <p class="input-disclaimer">AI 生成内容仅供参考，不构成专业建议</p>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue';

const props = defineProps<{
  modelValue: string;
  inputMode: number;
  isRecording: boolean;
  isExpanded: boolean;
  isSending: boolean;
  isUserLoggedIn: boolean;
  dbBars: number[];
}>();

const emit = defineEmits<{
  (e: 'update:modelValue', value: string): void;
  (e: 'send'): void;
  (e: 'toggle-voice'): void;
  (e: 'toggle-expanded'): void;
}>();

const placeholder = computed(() => props.inputMode === 2
  ? (props.isRecording ? '正在聆听...' : '正在聆听，说完后说发送提交')
  : '发消息...');

const handleInput = (event: Event) => {
  emit('update:modelValue', (event.target as HTMLTextAreaElement).value);
};
</script>

<style scoped>
.chat-input-area {
  z-index: 3;
  width: min(800px, calc(100% - 48px));
  max-width: calc(100% - 48px);
  bottom: 12px;
}

.textarea-wrapper {
  min-height: 92px;
  padding: 14px 16px 12px;
  border: 1px solid #ececef;
  border-radius: 24px;
  background: #ffffff;
  box-shadow: 0 10px 32px rgba(24, 24, 27, 0.08);
}

.chat-input {
  min-height: 40px;
  font-size: 16px;
  line-height: 1.6;
  color: #18181b;
}

.send-btn {
  min-width: 56px;
  height: 32px;
  border-radius: 999px;
  background: #18181b;
}

.voice-record-btn {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  background: #f4f4f5;
  color: #52525b;
}

.input-disclaimer {
  margin: 10px 0 0;
  color: #a1a1aa;
  font-size: 12px;
  line-height: 1.4;
  text-align: center;
}

@media (max-width: 960px) {
  .chat-input-area {
    width: calc(100% - 32px);
    max-width: calc(100% - 32px);
    bottom: 10px;
  }

  .textarea-wrapper {
    min-height: 96px;
    border-radius: 22px;
  }
}
</style>
