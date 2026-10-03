<template>
  <section class="knowledge-page">
    <div class="knowledge-inner">
      <div class="heading">
        <div>
          <h2>知识库</h2>
          <p>先上传资料，再解析入库。解析完成后即可在规划对话中引用。</p>
        </div>
        <label class="upload-button" :class="{ disabled: uploading || !isLoggedIn }">
          {{ uploading ? '正在上传…' : '上传文档' }}
          <input type="file" accept=".txt,.md,.pdf,.docx" :disabled="uploading || !isLoggedIn" @change="upload" />
        </label>
      </div>
      <p class="upload-hint">支持 TXT、Markdown、PDF、DOCX，单文件最大 10 MB。扫描版 PDF 需要先转为可选取文字的文件。</p>
      <div v-if="loading" class="empty">正在读取文档…</div>
      <div v-else-if="!files.length" class="empty">还没有文档。上传并解析后即可在规划对话中引用。</div>
      <div v-else class="file-list">
        <div v-for="file in files" :key="file.fileId" class="file-row">
          <div class="file-icon">{{ file.fileName.split('.').pop()?.toUpperCase() }}</div>
          <div class="file-info">
            <strong>{{ file.fileName }}</strong>
            <span>{{ formatSize(file.sizeBytes) }} · {{ formatDate(file.createTime) }}</span>
            <span v-if="file.errorMessage" class="file-error" :title="file.errorMessage">{{ file.errorMessage }}</span>
          </div>
          <div class="file-actions">
            <span v-if="file.status === 'ready'" class="ready-tag">可引用</span>
            <span v-else-if="['uploading', 'processing', 'deleting_parse', 'deleting_source'].includes(file.status)" class="pending-tag">
              {{ file.status === 'uploading' ? '上传中' : file.status === 'processing' ? '解析中' : '删除中' }}
            </span>
            <span v-else-if="file.status === 'upload_failed' || file.status === 'delete_failed'" class="pending-tag">{{ file.status === 'upload_failed' ? '上传失败' : '删除失败' }}</span>
            <button v-if="file.status === 'uploaded' || file.status === 'failed'" class="parse-button"
              :disabled="busyId !== null" @click="parse(file)">
              {{ busyId === file.fileId ? '处理中…' : file.status === 'failed' ? '重试解析' : '解析入库' }}
            </button>
            <button v-if="file.status === 'ready'" class="parse-button"
              :disabled="busyId !== null" @click="reparse(file)">重新解析</button>
            <button v-if="file.status === 'ready' || file.status === 'failed'" class="subtle-button"
              :disabled="busyId !== null" @click="removeParse(file)">删除解析</button>
            <button v-if="!['uploading', 'processing', 'deleting_parse', 'deleting_source'].includes(file.status)"
              class="danger-button" :disabled="busyId !== null" @click="removeSource(file)">删除源文件</button>
          </div>
        </div>
      </div>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { listDocumentsAPI, uploadDocumentAPI, parseDocumentAPI, reparseDocumentAPI,
  deleteDocumentParseAPI, deleteDocumentSourceAPI, type KnowledgeFile } from '../../api/documents';
import { tokenRef } from '../../utils/auth';

const isLoggedIn = computed(() => !!tokenRef.value);
const files = ref<KnowledgeFile[]>([]);
const loading = ref(false);
const uploading = ref(false);
const busyId = ref<number | null>(null);

const refresh = async () => {
  if (!isLoggedIn.value) { files.value = []; return; }
  loading.value = true;
  try { files.value = await listDocumentsAPI(); }
  catch { ElMessage.error('知识库列表加载失败'); }
  finally { loading.value = false; }
};
const upload = async (event: Event) => {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  input.value = '';
  if (!file) return;
  uploading.value = true;
  try {
    await uploadDocumentAPI(file);
    ElMessage.success('上传成功，请点击“解析入库”');
    await refresh();
  } catch (error: any) {
    ElMessage.error(error?.response?.data?.detail || error?.message || '上传失败');
  } finally { uploading.value = false; }
};
const parse = async (file: KnowledgeFile) => {
  busyId.value = file.fileId;
  try {
    await parseDocumentAPI(file.fileId);
    ElMessage.success('解析完成，可以在对话中引用');
  } catch (error: any) {
    ElMessage.error(error?.response?.data?.detail || error?.message || '解析失败');
  } finally {
    busyId.value = null;
    await refresh();
  }
};
const reparse = async (file: KnowledgeFile) => {
  busyId.value = file.fileId;
  try {
    await reparseDocumentAPI(file.fileId);
    ElMessage.success('重新解析完成');
  } catch (error: any) {
    ElMessage.error(error?.response?.data?.detail || error?.message || '重新解析失败');
  } finally {
    busyId.value = null;
    await refresh();
  }
};
const removeParse = async (file: KnowledgeFile) => {
  try {
    await ElMessageBox.confirm(`删除“${file.fileName}”的解析结果？原文件会保留，可再次解析。`, '删除解析', { type: 'warning' });
  } catch { return; }
  busyId.value = file.fileId;
  try {
    await deleteDocumentParseAPI(file.fileId);
    ElMessage.success('解析结果已删除');
  } catch (error: any) {
    ElMessage.error(error?.response?.data?.detail || error?.message || '删除解析失败');
  } finally {
    busyId.value = null;
    await refresh();
  }
};
const removeSource = async (file: KnowledgeFile) => {
  try {
    await ElMessageBox.confirm(`删除“${file.fileName}”的源文件及全部解析结果？此操作无法撤销。`, '删除源文件', { type: 'warning' });
  } catch { return; }
  busyId.value = file.fileId;
  try {
    await deleteDocumentSourceAPI(file.fileId);
    ElMessage.success('源文件及解析结果已删除');
  } catch (error: any) {
    ElMessage.error(error?.response?.data?.detail || error?.message || '删除源文件失败');
  } finally {
    busyId.value = null;
    await refresh();
  }
};
const formatSize = (bytes: number) => bytes >= 1024 * 1024
  ? `${(bytes / 1024 / 1024).toFixed(1)} MB` : `${Math.max(1, Math.round(bytes / 1024))} KB`;
const formatDate = (value: string | null) => value ? new Date(value).toLocaleString('zh-CN') : '';
watch(tokenRef, refresh);
onMounted(refresh);
</script>

<style scoped>
.knowledge-page { flex: 1; overflow: auto; background: #fafafa; padding: 44px 28px; }
.knowledge-inner { max-width: 920px; margin: 0 auto; }
.heading { display: flex; justify-content: space-between; align-items: center; gap: 24px; }
h2 { margin: 0 0 8px; font-size: 30px; color: #18181b; }
.heading p, .upload-hint { color: #71717a; font-size: 14px; }
.upload-hint { margin: 14px 0 26px; }
.upload-button { position: relative; padding: 11px 19px; flex-shrink: 0; border-radius: 12px; color: white; background: #18181b; cursor: pointer; font-size: 14px; }
.upload-button.disabled { opacity: .55; cursor: default; }
.upload-button input { position: absolute; width: 1px; height: 1px; opacity: 0; }
.file-list { background: white; border: 1px solid #e4e4e7; border-radius: 16px; overflow: hidden; }
.file-row { display: flex; align-items: center; gap: 16px; padding: 18px 20px; }
.file-row + .file-row { border-top: 1px solid #f4f4f5; }
.file-icon { width: 46px; height: 46px; border-radius: 11px; background: #eff6ff; color: #2563eb; display: grid; place-items: center; font-size: 11px; font-weight: 700; }
.file-info { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 5px; }
.file-info strong { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 14px; }
.file-info span { color: #a1a1aa; font-size: 12px; }
.ready-tag { color: #15803d; background: #f0fdf4; border-radius: 99px; padding: 5px 10px; font-size: 12px; }
.parse-button { border: 0; border-radius: 9px; background: #eff6ff; color: #1d4ed8; padding: 8px 12px; cursor: pointer; white-space: nowrap; }
.parse-button:disabled { opacity: .6; cursor: default; }
.file-actions { display: flex; align-items: center; justify-content: flex-end; flex-wrap: wrap; gap: 8px; }
.subtle-button, .danger-button { border: 1px solid #e4e4e7; border-radius: 9px; background: #fff; color: #52525b; padding: 7px 11px; cursor: pointer; white-space: nowrap; }
.danger-button { color: #dc2626; border-color: #fecaca; }
.subtle-button:disabled, .danger-button:disabled { opacity: .6; cursor: default; }
.pending-tag { color: #71717a; font-size: 12px; white-space: nowrap; }
.file-error { color: #dc2626 !important; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.empty { padding: 76px 20px; text-align: center; color: #a1a1aa; background: white; border: 1px dashed #d4d4d8; border-radius: 16px; }
@media (max-width: 700px) { .knowledge-page { padding: 22px 16px; } .heading { align-items: flex-start; flex-direction: column; } }
</style>
