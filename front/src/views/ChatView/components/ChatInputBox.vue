<template>
  <div class="chat-input-area" :class="{ 'is-expanded': isExpanded }">
    <div v-if="selectedDocuments.length" class="reference-bubbles" role="list" aria-label="已引用文档">
      <div v-for="item in selectedDocuments" :key="item.id" class="reference-bubble" role="listitem" :title="item.name">
        <span class="bubble-icon" aria-hidden="true">▤</span>
        <span class="bubble-name">{{ item.name }}</span>
        <button type="button" class="bubble-remove" :disabled="isSending"
          :aria-label="`移除引用文档 ${item.name}`" :title="`移除 ${item.name}`" @click="removeDocument(item.id)">×</button>
      </div>
    </div>
    <div class="textarea-wrapper">
      <textarea
        :value="modelValue"
        class="chat-input"
        :placeholder="placeholder"
        @input="handleInput"
        @keydown.enter.exact.prevent="$emit('send')"
        :disabled="isSending || !isUserLoggedIn"
        :rows="isExpanded ? 8 : 2"
      ></textarea>
      <button type="button" class="expand-toggle" :aria-label="isExpanded ? '收起输入框' : '展开输入框'"
        :aria-expanded="isExpanded" @click="$emit('toggle-expanded')">
        {{ isExpanded ? '收起' : '展开' }}
      </button>
      <div class="input-actions">
        <div ref="documentControlRef" class="document-control">
          <button class="reference-btn" type="button" :disabled="isSending || !isUserLoggedIn" @click="toggleDocuments">
            引用文档{{ documentIds.length ? ` (${documentIds.length})` : '' }}
          </button>
          <div v-if="showDocuments" class="document-picker">
            <div class="picker-heading">选择本轮规划引用的文档</div>
            <p v-if="loadingDocuments">加载中…</p>
            <p v-else-if="!documents.length">知识库暂无文档</p>
            <label v-for="item in documents" :key="item.fileId" class="document-option">
              <input type="checkbox" :checked="documentIds.includes(item.fileId)" :disabled="!documentIds.includes(item.fileId) && documentIds.length >= 5" @change="toggleDocument(item.fileId)" />
              <span :title="item.fileName">{{ item.fileName }}</span>
            </label>
            <RouterLink to="/knowledge" class="knowledge-link" @click="showDocuments = false">打开知识库上传文档 →</RouterLink>
          </div>
        </div>
        <div class="send-controls">
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
      </div>
    </div>
    <p class="input-disclaimer">AI 生成内容仅供参考，不构成专业建议</p>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue';
import { ElMessage } from 'element-plus';
import { listDocumentsAPI, type KnowledgeFile } from '../../../api/documents';

const props = defineProps<{
  modelValue: string;
  inputMode: number;
  isRecording: boolean;
  isExpanded: boolean;
  isSending: boolean;
  isUserLoggedIn: boolean;
  dbBars: number[];
  documentIds: number[];
}>();

const emit = defineEmits<{
  (e: 'update:modelValue', value: string): void;
  (e: 'send'): void;
  (e: 'toggle-voice'): void;
  (e: 'toggle-expanded'): void;
  (e: 'update:documentIds', value: number[]): void;
}>();

const documents = ref<KnowledgeFile[]>([]);
const loadingDocuments = ref(false);
const showDocuments = ref(false);
const documentControlRef = ref<HTMLElement | null>(null);
const closeDocumentsOnOutsideClick = (event: PointerEvent) => {
  if (showDocuments.value && event.target instanceof Node && !documentControlRef.value?.contains(event.target)) {
    showDocuments.value = false;
  }
};
const closeDocumentsOnEscape = (event: KeyboardEvent) => {
  if (event.key === 'Escape') showDocuments.value = false;
};
onMounted(() => {
  document.addEventListener('pointerdown', closeDocumentsOnOutsideClick);
  document.addEventListener('keydown', closeDocumentsOnEscape);
});
onUnmounted(() => {
  document.removeEventListener('pointerdown', closeDocumentsOnOutsideClick);
  document.removeEventListener('keydown', closeDocumentsOnEscape);
});
const selectedDocuments = computed(() => props.documentIds.map(id => ({
  id,
  name: documents.value.find(item => item.fileId === id)?.fileName || `文档 #${id}`
})));
const toggleDocuments = async () => {
  showDocuments.value = !showDocuments.value;
  if (!showDocuments.value) return;
  loadingDocuments.value = true;
  try {
    documents.value = (await listDocumentsAPI()).filter(item => item.status === 'ready');
    const readyIds = new Set(documents.value.map(item => item.fileId));
    const validSelection = props.documentIds.filter(id => readyIds.has(id));
    if (validSelection.length !== props.documentIds.length) emit('update:documentIds', validSelection);
  }
  catch { ElMessage.error('文档列表加载失败'); }
  finally { loadingDocuments.value = false; }
};
const toggleDocument = (id: number) => {
  emit('update:documentIds', props.documentIds.includes(id)
    ? props.documentIds.filter(item => item !== id) : [...props.documentIds, id]);
};
const removeDocument = (id: number) => {
  emit('update:documentIds', props.documentIds.filter(item => item !== id));
};

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

.reference-bubbles {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin: 0 0 10px;
}

.reference-bubble {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  max-width: min(260px, 100%);
  min-height: 34px;
  padding: 4px 6px 4px 11px;
  border: 1px solid #dbeafe;
  border-radius: 999px;
  background: #eff6ff;
  color: #1e40af;
  box-shadow: 0 2px 8px rgba(37, 99, 235, 0.08);
  box-sizing: border-box;
}

.bubble-icon { flex-shrink: 0; font-size: 15px; line-height: 1; }
.bubble-name { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 12px; font-weight: 600; }
.bubble-remove {
  flex-shrink: 0;
  width: 22px;
  height: 22px;
  display: grid;
  place-items: center;
  padding: 0;
  border: 0;
  border-radius: 50%;
  background: transparent;
  color: #64748b;
  cursor: pointer;
  font-size: 18px;
  line-height: 1;
}
.bubble-remove:hover:not(:disabled), .bubble-remove:focus-visible { background: #dbeafe; color: #1e3a8a; }
.bubble-remove:disabled { opacity: 0.5; cursor: default; }

.textarea-wrapper {
  min-height: 0;
  padding: 14px 16px 12px;
  border: 1px solid #ececef;
  border-radius: 24px;
  background: #ffffff;
  box-shadow: 0 10px 32px rgba(24, 24, 27, 0.08);
}

.chat-input {
  display: block;
  height: 48px;
  min-height: 48px;
  max-height: 48px;
  padding: 2px 48px 0 2px;
  overflow-y: auto;
  font-size: 16px;
  line-height: 1.6;
  color: #18181b;
}
.is-expanded .chat-input {
  height: 180px;
  min-height: 180px;
  max-height: 180px;
}
.expand-toggle {
  position: absolute;
  top: 14px;
  right: 16px;
  padding: 2px 7px;
  border: 0;
  border-radius: 6px;
  background: #f4f4f5;
  color: #71717a;
  font-size: 12px;
  cursor: pointer;
}
.expand-toggle:hover { background: #e4e4e7; color: #27272a; }
.input-actions {
  position: static;
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  justify-content: flex-start;
  gap: 10px;
  margin-top: 8px;
}
.send-controls { display: flex; align-items: center; gap: 10px; flex-shrink: 0; margin-left: auto; }
.voice-controls { flex-shrink: 0; }

.send-btn {
  min-width: 56px;
  height: 32px;
  border-radius: 999px;
  background: #18181b;
}
.document-control { position: relative; }
.reference-btn { border: 0; background: #f4f4f5; color: #52525b; border-radius: 99px; padding: 7px 12px; font-size: 12px; cursor: pointer; }
.reference-btn:disabled { opacity: .5; cursor: default; }
.document-picker { position: absolute; bottom: 38px; left: 0; width: min(320px, 80vw); max-height: 300px; overflow: auto; z-index: 10; background: #fff; padding: 12px; border: 1px solid #e4e4e7; border-radius: 12px; box-shadow: 0 12px 32px #18181b22; }
.picker-heading { font-size: 13px; font-weight: 650; padding: 4px 6px 8px; }
.document-picker p { color: #71717a; font-size: 12px; padding: 0 6px; }
.document-option { display: flex; align-items: center; gap: 8px; padding: 7px 6px; font-size: 13px; cursor: pointer; }
.document-option span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.knowledge-link { display: block; padding: 10px 6px 4px; color: #2563eb; font-size: 12px; text-decoration: none; }

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
    min-height: 0;
    border-radius: 22px;
  }
}
</style>
