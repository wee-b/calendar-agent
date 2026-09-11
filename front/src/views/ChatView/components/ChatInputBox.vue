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
