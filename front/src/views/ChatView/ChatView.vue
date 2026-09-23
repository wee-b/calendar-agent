<template>
  <div class="chat-view">
    <ChatPanel
      @refresh="emit('refresh')"
      @title-change="title => emit('title-change', title)"
    />
  </div>
</template>

<script setup lang="ts">
import ChatPanel from './components/ChatPanel.vue';

const emit = defineEmits<{
  (e: 'refresh'): void;
  (e: 'title-change', title: string): void;
}>();
</script>

<style scoped>
.chat-view {
  flex: 1;
  min-width: 0;
  min-height: 0;
  height: 100%;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
</style>
