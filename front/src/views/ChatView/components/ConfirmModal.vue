<template>
  <div class="confirm-modal-overlay">
    <div class="confirm-modal">
      <p>{{ title }}</p>
      <p v-if="preview" class="confirm-preview">{{ preview }}</p>
      <div class="confirm-actions">
        <button class="confirm-btn danger" @click="$emit('confirm')">{{ confirmText }}</button>
        <button class="confirm-btn cancel" @click="$emit('cancel')">取消</button>
      </div>
      <p v-if="hint" class="confirm-hint">{{ hint }}</p>
    </div>
  </div>
</template>

<script setup lang="ts">
withDefaults(defineProps<{
  title: string;
  confirmText?: string;
  preview?: string;
  hint?: string;
}>(), {
  confirmText: '确认'
});

defineEmits<{
  (e: 'confirm'): void;
  (e: 'cancel'): void;
}>();
</script>
