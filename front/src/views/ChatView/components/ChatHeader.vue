<template>
  <div class="chat-header">
    <div class="session-selector" @click="$emit('toggle-dropdown')">
      <h2>
        {{ isUserLoggedIn ? (title || '新对话') : '语音助手' }}
        <span v-if="isUserLoggedIn" class="arrow" :class="{ open: isDropdownOpen }">⌄</span>
      </h2>
    </div>

    <button class="new-chat-btn" @click="$emit('new-session')" title="开启新对话">
      新对话
    </button>
  </div>
</template>

<script setup lang="ts">
defineProps<{
  title: string;
  isUserLoggedIn: boolean;
  isDropdownOpen: boolean;
}>();

defineEmits<{
  (e: 'toggle-dropdown'): void;
  (e: 'new-session'): void;
}>();
</script>
