<template>
  <div v-if="references?.length" class="message-documents" role="list" aria-label="本条消息引用的文档">
    <span v-for="item in references" :key="item.fileId" class="message-document" role="listitem" :title="item.fileName">
      <span aria-hidden="true">▤</span>
      <span class="document-name">{{ item.fileName }}</span>
    </span>
  </div>
</template>

<script setup lang="ts">
import type { DocumentReference } from '../../../api/documents';
defineProps<{ references?: DocumentReference[] }>();
</script>

<style scoped>
.message-documents { display: flex; flex-wrap: wrap; gap: 6px; margin-bottom: 8px; }
.message-document {
  display: inline-flex; align-items: center; gap: 6px; max-width: 100%;
  padding: 4px 10px; border: 1px solid #bfdbfe; border-radius: 16px;
  background: #eff6ff; color: #1e40af; font-size: 12px; line-height: 1.5;
  box-sizing: border-box;
}
.document-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
</style>
