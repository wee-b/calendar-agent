<template>
  <div class="chat-view">
    <ChatPanel @refresh="emit('refresh')" />
  </div>
</template>

<script setup lang="ts">
import ChatPanel from './components/ChatPanel.vue';

const emit = defineEmits<{ (e: 'refresh'): void }>();
</script>

<style scoped>
.chat-view {
  flex: 1;
  display: flex;
  overflow: hidden;
}
</style>
