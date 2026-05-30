<template>
  <aside class="sidebar right-sidebar" :class="{ 'is-collapsed': !isOpen }">
    <div class="sidebar-content" ref="sidebarContentRef">

      <div class="chat-header">
        <div class="session-selector" @click="toggleDropdown">
          <h2>
            {{ isUserLoggedIn ? (currentSessionTitle || '新对话') : '语音助手' }}
            <span v-if="isUserLoggedIn" class="arrow" :class="{ open: isDropdownOpen }">▼</span>
          </h2>
        </div>

        <button class="new-chat-btn" @click="createNewSession" title="开启新对话">
          新对话
        </button>
      </div>

      <div v-if="isDropdownOpen" class="dropdown-overlay" @click="isDropdownOpen = false"></div>
      <div v-if="isDropdownOpen" class="session-dropdown">
        <div
            v-for="s in sessions"
            :key="s.sessionId"
            class="session-item"
            :class="{ active: s.sessionId === currentSessionId }"
            @click="selectSession(s)"
        >
          <span class="session-name" :title="s.title">{{ s.title || '新对话' }}</span>
          <button class="delete-btn" @click.stop="promptDelete(s.sessionId)" title="删除对话">✕</button>
        </div>
        <div v-if="sessions.length === 0" class="empty-sessions">暂无历史对话</div>
      </div>

      <div class="chat-history" ref="chatHistoryRef">

        <div v-if="messages.length === 0" class="empty-chat">
          <div class="empty-avatar">AI</div>
          <p>你好！我是你的智能日程助手。<br>你可以让我“帮我安排明天的会议”，或者“取消后天的日程”。</p>
        </div>

        <div
            v-for="(msg, index) in messages"
            :key="index"
            class="msg-bubble"
            :class="isUserRole(msg.role) ? 'user-msg' : 'ai-msg'"
        >
          <span class="avatar">{{ isUserRole(msg.role) ? '我' : 'AI' }}</span>

          <div class="bubble-content">
            <p v-if="!msg.loading">{{ msg.content }}</p>
            <div v-else class="typing-indicator">
              <span></span><span></span><span></span>
            </div>

            <div class="msg-actions" v-if="!msg.loading && !isUserRole(msg.role)">
              <span class="action-icon" @click="handleCopy(msg.content)">复制</span>
              <span class="action-icon" @click="handleRead(msg.content)">朗读</span>
              <span class="action-icon danger" @click="handleDeleteLastRound">撤回</span>
            </div>
          </div>
        </div>
      </div>

      <div class="chat-input-area" :class="{ 'is-expanded': isExpanded }">
        <div class="textarea-wrapper">
          <textarea
              v-model="inputText"
              class="chat-input"
              :placeholder="voiceEnabled ? (isRecording ? '正在聆听...' : '正在聆听，说完后说【发送】提交...') : '语音已关闭，输入指令或点击🎤开启'"
              @keydown.enter.exact.prevent="handleSend"
              :disabled="isSending || !isUserLoggedIn"
              :rows="isExpanded ? 10 : 3"
          ></textarea>
          <div class="input-actions">
            <button
                class="voice-record-btn"
                :class="{ recording: isRecording }"
                @click="toggleVoice"
                :disabled="isSending || !isUserLoggedIn"
            >
              {{ voiceEnabled ? '🎤' : '🔇' }}
            </button>
            <button
                class="send-btn"
                @click="handleSend"
                :disabled="isSending || !inputText.trim() || !isUserLoggedIn"
            >
              发送
            </button>
          </div>
          <span class="expand-icon" @click="isExpanded = !isExpanded" :title="isExpanded ? '收起' : '展开'">
            {{ isExpanded ? '↕' : '↕' }}
          </span>
        </div>
      </div>

      <div v-if="confirmSendVisible" class="confirm-modal-overlay">
        <div class="confirm-modal">
          <p>确认发送吗？</p>
          <p class="confirm-preview">{{ inputText }}</p>
          <div class="confirm-actions">
            <button class="confirm-btn danger" @click="cleanupConfirmRecognition(); confirmSendVisible = false; sendGuard = false; handleSend()">确认发送</button>
            <button class="confirm-btn cancel" @click="cleanupConfirmRecognition(); confirmSendVisible = false; sendGuard = false">取消</button>
          </div>
          <p class="confirm-hint">也可以说"确认"或"取消"来控制</p>
        </div>
      </div>

      <div v-if="showDeleteConfirm" class="confirm-modal-overlay">
        <div class="confirm-modal">
          <p>确定要删除该对话吗？</p>
          <div class="confirm-actions">
            <button class="confirm-btn danger" @click="executeDelete">删除</button>
            <button class="confirm-btn cancel" @click="showDeleteConfirm = false">取消</button>
          </div>
        </div>
      </div>

    </div>
  </aside>
</template>

<script setup lang="ts">
import { ref, computed, nextTick, onMounted, watch } from 'vue';
import { ElMessage } from 'element-plus';
import {
  newSessionAPI, sendChatAPI, getSessionsAPI, getHistoryAPI, deleteSessionAPI, deleteLastRoundAPI, type ChatSessionVO
} from '../../../api/chat';
import { tokenRef } from '../../../utils/auth';

const props = defineProps<{ isOpen: boolean }>();
const emit = defineEmits<{ (e: 'refresh'): void }>();
const isUserLoggedIn = computed(() => !!tokenRef.value);

interface ChatMessage { role: string; content: string; loading?: boolean; }

const sessions = ref<ChatSessionVO[]>([]);
const currentSessionId = ref<string | null>(null);
const messages = ref<ChatMessage[]>([]);
const inputText = ref('');
const isSending = ref(false);

const isDropdownOpen = ref(false);
const showDeleteConfirm = ref(false);
const pendingDeleteId = ref<string | null>(null);
const chatHistoryRef = ref<HTMLElement | null>(null);

const isRecording = ref(false);
const isExpanded = ref(false);
const confirmSendVisible = ref(false);
const voiceEnabled = ref(true);
let recognition: any = null;
let confirmRecognition: any = null;
let sendGuard = false;
let voiceTimer: any = null;
const VOICE_TIMEOUT = 10 * 60 * 1000; // 10分钟

const currentSessionTitle = computed(() => {
  const session = sessions.value.find(s => s.sessionId === currentSessionId.value);
  return session?.title || '新对话';
});

const isUserRole = (role: string) => role.toLowerCase() === 'user';

const scrollToBottom = async () => {
  await nextTick();
  if (chatHistoryRef.value) {
    chatHistoryRef.value.scrollTop = chatHistoryRef.value.scrollHeight;
  }
};

const toggleDropdown = () => { if (isUserLoggedIn.value) isDropdownOpen.value = !isDropdownOpen.value; };

// ================= 会话管理 =================
const fetchSessions = async () => {
  if (!isUserLoggedIn.value) return;
  try {
    const res = await getSessionsAPI();
    sessions.value = res || [];
  } catch (error) {}
};

const selectSession = async (session: ChatSessionVO) => {
  currentSessionId.value = session.sessionId;
  isDropdownOpen.value = false;
  messages.value = [];
  try {
    const history = await getHistoryAPI(session.sessionId);
    messages.value = history.map(h => ({ role: h.role, content: h.content }));
    scrollToBottom();
  } catch (error) {}
};

const createNewSession = async () => {
  if (!isUserLoggedIn.value) { ElMessage.warning('请先登录'); return; }
  isDropdownOpen.value = false;
  messages.value = [];
  try {
    const res = await newSessionAPI();
    currentSessionId.value = res.sessionId || Object.values(res)[0];
    await fetchSessions();
  } catch (error) {}
};

// ================= 删除操作 =================
const promptDelete = (sessionId: string) => {
  isDropdownOpen.value = false;
  pendingDeleteId.value = sessionId;
  showDeleteConfirm.value = true;
};

const executeDelete = async () => {
  if (!pendingDeleteId.value) return;
  try {
    await deleteSessionAPI(pendingDeleteId.value);
    ElMessage.success('已删除对话');
    if (currentSessionId.value === pendingDeleteId.value) {
      currentSessionId.value = null;
      messages.value = [];
      await createNewSession();
    } else {
      await fetchSessions();
    }
  } catch (error) {}
  finally {
    showDeleteConfirm.value = false;
    pendingDeleteId.value = null;
  }
};

// ================= 消息交互：复制 / 朗读 / 撤回 =================
const handleCopy = async (text: string) => {
  try {
    await navigator.clipboard.writeText(text);
    ElMessage.success('已复制到剪贴板');
  } catch (error) {
    ElMessage.error('复制失败，请手动复制');
  }
};

const handleRead = (text: string) => {
  if ('speechSynthesis' in window) {
    window.speechSynthesis.cancel();
    const utterance = new SpeechSynthesisUtterance(text);
    utterance.lang = 'zh-CN';
    window.speechSynthesis.speak(utterance);
  } else {
    ElMessage.warning('当前浏览器不支持语音播报');
  }
};

const handleDeleteLastRound = async () => {
  if (!currentSessionId.value) return;
  try {
    await deleteLastRoundAPI(currentSessionId.value);
    ElMessage.success('已撤回上一轮对话');
    const history = await getHistoryAPI(currentSessionId.value);
    messages.value = history.map(h => ({ role: h.role, content: h.content }));
    scrollToBottom();
  } catch (error) {}
};

// ================= 语音识别录入 =================
const clearVoiceTimer = () => {
  if (voiceTimer) { clearTimeout(voiceTimer); voiceTimer = null; }
};

const resetVoiceTimer = () => {
  clearVoiceTimer();
  voiceTimer = setTimeout(() => {
    voiceEnabled.value = false;
    stopVoice();
    cleanupConfirmRecognition();
  }, VOICE_TIMEOUT);
};

const toggleVoice = () => {
  if (voiceEnabled.value) {
    voiceEnabled.value = false;
    clearVoiceTimer();
    stopVoice();
    cleanupConfirmRecognition();
  } else {
    voiceEnabled.value = true;
    resetVoiceTimer();
    startVoice();
  }
};

const startVoice = () => {
  if (!isUserLoggedIn.value || !voiceEnabled.value) return;

  const SpeechRecognition = (window as any).SpeechRecognition || (window as any).webkitSpeechRecognition;
  if (!SpeechRecognition) {
    ElMessage.warning('您的浏览器不支持语音识别功能');
    voiceEnabled.value = false;
    return;
  }

  try { if (recognition) recognition.stop(); } catch {}

  recognition = new SpeechRecognition();
  recognition.lang = 'zh-CN';
  recognition.interimResults = true;
  recognition.continuous = true;

  isRecording.value = true;
  sendGuard = false;

  recognition.onresult = (event: any) => {
    let currentText = '';
    for (let i = 0; i < event.results.length; i++) {
      currentText += event.results[i][0].transcript;
    }
    inputText.value = currentText.replace(/[。！？，、；：]\)\}»』】〉》]+$/g, '').trim();
  };

  recognition.onerror = (event: any) => {
    if (event.error !== 'aborted') {
      ElMessage.error('语音识别出错');
    }
    isRecording.value = false;
  };

  recognition.onend = () => {
    isRecording.value = false;
  };

  recognition.start();
};

const stopVoice = () => {
  if (recognition) {
    try { recognition.stop(); } catch {}
    recognition = null;
  }
  isRecording.value = false;
};

// ================= 语音发送确认 =================
const cleanupConfirmRecognition = () => {
  if (confirmRecognition) {
    try { confirmRecognition.stop(); } catch {}
    confirmRecognition = null;
  }
};

const startConfirmRecognition = () => {
  cleanupConfirmRecognition();
  const SpeechRecognition = (window as any).SpeechRecognition || (window as any).webkitSpeechRecognition;
  if (!SpeechRecognition) return;

  confirmRecognition = new SpeechRecognition();
  confirmRecognition.lang = 'zh-CN';
  confirmRecognition.interimResults = false;
  confirmRecognition.continuous = false;

  confirmRecognition.onresult = (event: any) => {
    const text = event.results[0][0].transcript.trim();
    if (/确认|发送|好的|可以|行|没问题/.test(text)) {
      cleanupConfirmRecognition();
      confirmSendVisible.value = false;
      sendGuard = false;
      handleSend();
    } else if (/取消|不要|算了|不了/.test(text)) {
      cleanupConfirmRecognition();
      confirmSendVisible.value = false;
      sendGuard = false;
    }
  };

  confirmRecognition.onerror = () => {
    cleanupConfirmRecognition();
  };

  confirmRecognition.onend = () => {
    if (confirmSendVisible.value) {
      setTimeout(() => {
        if (confirmSendVisible.value && confirmRecognition) {
          try { confirmRecognition.start(); } catch {}
        }
      }, 300);
    }
  };

  confirmRecognition.start();
};

watch(inputText, (newVal) => {
  if (!isRecording.value || sendGuard) return;
  const idx = newVal.indexOf('发送');
  if (idx === -1) return;

  sendGuard = true;
  inputText.value = newVal.substring(0, idx).trim();
  stopVoice();
  confirmSendVisible.value = true;
  startConfirmRecognition();
});

// ================= 发送消息 =================
const CHAT_TIMEOUT = 10000;

const handleSend = async () => {
  let text = inputText.value.trim();
  text = text.replace(/发送[。！？，、；：]*$/g, '').trim();
  if (!text || isSending.value || !isUserLoggedIn.value) return;

  if (!currentSessionId.value) {
    await createNewSession();
    if (!currentSessionId.value) return;
  }

  stopVoice();
  messages.value.push({ role: 'user', content: text });
  inputText.value = '';
  scrollToBottom();

  messages.value.push({ role: 'ai', content: '', loading: true });
  isSending.value = true;
  scrollToBottom();

  let slowTimer: any = null;
  try {
    slowTimer = setTimeout(() => {
      ElMessage.warning('响应较慢，请耐心等待...');
    }, CHAT_TIMEOUT);

    const res = await sendChatAPI({
      sessionId: currentSessionId.value,
      message: text
    });
    clearTimeout(slowTimer);

    const lastMsg = messages.value[messages.value.length - 1];
    if (lastMsg && lastMsg.loading) {
      lastMsg.loading = false;
      lastMsg.content = res.aiResult || '操作已完成';
    }
    await nextTick();
    scrollToBottom();
    emit('refresh');
    handleRead(lastMsg?.content || res.aiResult || '操作已完成');
    fetchSessions();
  } catch (error) {
    clearTimeout(slowTimer);
    const lastMsg = messages.value[messages.value.length - 1];
    if (lastMsg && lastMsg.loading) {
      lastMsg.loading = false;
      lastMsg.content = '抱歉，网络开小差了，请重试。';
    }
  } finally {
    isSending.value = false;
    scrollToBottom();
    inputText.value = '';
    if (voiceEnabled.value) {
      resetVoiceTimer();
      setTimeout(() => startVoice(), 400);
    }
  }
};

// ================= 生命周期 =================
onMounted(async () => {
  if (isUserLoggedIn.value) {
    await fetchSessions();
    if (sessions.value.length > 0) selectSession(sessions.value[0]);
    else createNewSession();
    if (voiceEnabled.value) { resetVoiceTimer(); startVoice(); }
  }
});

watch(isUserLoggedIn, async (newVal) => {
  if (newVal) {
    await fetchSessions();
    if (sessions.value.length > 0) selectSession(sessions.value[0]);
    else createNewSession();
    if (voiceEnabled.value) { resetVoiceTimer(); startVoice(); }
  } else {
    clearVoiceTimer();
    stopVoice();
    sessions.value = [];
    messages.value = [];
    currentSessionId.value = null;
  }
});
</script>

<style scoped>
/* 侧边栏基础结构 */
.sidebar { width: 20%; min-width: 280px; max-width: 400px; background-color: #fdfae9; transition: all 0.3s; display: flex; flex-direction: column; z-index: 5; overflow: hidden; }
.right-sidebar { border-left: 2px solid #d3c4a1; }
.sidebar-content { position: relative; width: 100%; padding: 20px 20px 0 20px; min-width: 280px; height: 100%; box-sizing: border-box; display: flex; flex-direction: column; }
.sidebar.is-collapsed { width: 0 !important; min-width: 0 !important; border: none; }

/* 头部 */
.chat-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; border-bottom: 2px dashed #d3c4a1; padding-bottom: 12px; }
.session-selector { flex: 1; cursor: pointer; overflow: hidden; user-select: none; }
.session-selector h2 { color: #5c4b37; font-size: 1.1rem; margin: 0; display: flex; align-items: center; gap: 6px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.arrow { font-size: 10px; color: #b5a992; transition: transform 0.2s; }
.arrow.open { transform: rotate(180deg); }
.new-chat-btn { background: none; border: 1px solid #d3c4a1; color: #5c4b37; font-size: 13px; font-weight: bold; padding: 4px 10px; border-radius: 4px; cursor: pointer; transition: all 0.2s; white-space: nowrap; margin-left: 10px; }
.new-chat-btn:hover { background: #eaddc4; }

/* 下拉菜单 */
.dropdown-overlay { position: absolute; top: 56px; left: 0; right: 0; bottom: 0; z-index: 10; }
.session-dropdown { position: absolute; top: 56px; left: 20px; right: 20px; background: #fdfae9; border: 2px solid #d3c4a1; border-radius: 8px; box-shadow: 0 8px 24px rgba(92, 75, 55, 0.15); max-height: 250px; overflow-y: auto; z-index: 11; }
.session-dropdown::-webkit-scrollbar { width: 4px; }
.session-dropdown::-webkit-scrollbar-thumb { background: #d3c4a1; border-radius: 2px; }
.session-item { display: flex; justify-content: space-between; align-items: center; padding: 12px 14px; border-bottom: 1px solid #f2ecd9; cursor: pointer; transition: background 0.2s; }
.session-item:last-child { border-bottom: none; }
.session-item:hover { background: #fcf9ee; }
.session-item.active { background: #eaddc4; font-weight: bold; }
.session-name { flex: 1; color: #5c4b37; font-size: 13px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.delete-btn { background: none; border: none; color: #b5a992; font-size: 14px; cursor: pointer; padding: 4px; border-radius: 4px; transition: all 0.2s; }
.delete-btn:hover { color: #bc423f; background: #fdfae9; }
.empty-sessions { padding: 20px; text-align: center; color: #b5a992; font-size: 13px; }

/* 聊天历史区 */
.chat-history { flex: 1; overflow-y: auto; display: flex; flex-direction: column; gap: 16px; padding-right: 5px; padding-bottom: 20px; }
.chat-history::-webkit-scrollbar { width: 4px; }
.chat-history::-webkit-scrollbar-thumb { background: #d3c4a1; border-radius: 2px; }
.empty-chat { display: flex; flex-direction: column; align-items: center; justify-content: center; height: 100%; color: #b5a992; text-align: center; }
.empty-avatar { width: 48px; height: 48px; background: #eaddc4; border-radius: 50%; display: flex; align-items: center; justify-content: center; font-weight: bold; color: #5c4b37; font-size: 18px; margin-bottom: 16px; }
.empty-chat p { margin: 0; font-size: 13px; line-height: 1.6; }

/* 对话气泡通用样式 */
.msg-bubble { display: flex; flex-direction: column; gap: 4px; max-width: 90%; }
.avatar { font-size: 12px; color: #b5a992; font-weight: bold; }
.bubble-content { border-radius: 12px; padding: 10px 14px; box-shadow: 0 2px 6px rgba(92, 75, 55, 0.05); }
.bubble-content p { margin: 0; font-size: 14px; line-height: 1.5; color: #5c4b37; word-break: break-word; white-space: pre-wrap; }

.user-msg { align-self: flex-end; align-items: flex-end; }
.user-msg .bubble-content { background-color: #eaddc4; border-bottom-right-radius: 2px; }
.ai-msg { align-self: flex-start; align-items: flex-start; }
.ai-msg .bubble-content { background-color: #fcf9ee; border: 1px solid #d3c4a1; border-bottom-left-radius: 2px; }

/* 气泡下方工具栏样式 (纯文本) */
.msg-actions { display: flex; gap: 16px; margin-top: 10px; padding-top: 8px; border-top: 1px dashed #eaddc4; }
.action-icon { font-size: 12px; font-weight: bold; color: #b5a992; cursor: pointer; transition: color 0.2s; }
.action-icon:hover { color: #5c4b37; text-decoration: underline; }
.action-icon.danger:hover { color: #bc423f; }

/* 加载动画 */
.typing-indicator { display: flex; gap: 4px; align-items: center; height: 20px; }
.typing-indicator span { width: 6px; height: 6px; background-color: #b5a992; border-radius: 50%; animation: typing 1.4s infinite ease-in-out both; }
.typing-indicator span:nth-child(1) { animation-delay: -0.32s; }
.typing-indicator span:nth-child(2) { animation-delay: -0.16s; }
@keyframes typing { 0%, 80%, 100% { transform: scale(0); } 40% { transform: scale(1); background-color: #5c4b37; } }

/* 输入区域布局 */
.chat-input-area { padding: 10px 0; border-top: 1px solid #eaddc4; background-color: #fdfae9; z-index: 2; flex-shrink: 0; }

.textarea-wrapper { position: relative; }

/* 修改点1：增加 padding-bottom 到 42px，为底部的动作按钮留出安全的文字防踩踏空间 */
.chat-input { width: 100%; padding: 8px 32px 42px 12px; background-color: #fcf9ee; border: 1px solid #d3c4a1; border-radius: 6px; font-size: 13px; color: #5c4b37; outline: none; transition: border-color 0.2s; line-height: 1.5; resize: none; box-sizing: border-box; font-family: inherit; }
.chat-input:focus { border-color: #5c4b37; }
.chat-input:disabled { background-color: #f2ecd9; cursor: not-allowed; }

.expand-icon { position: absolute; top: 8px; right: 8px; font-size: 13px; color: #b5a992; cursor: pointer; padding: 2px 6px; border-radius: 3px; transition: all 0.2s; user-select: none; z-index: 1; }
.expand-icon:hover { color: #5c4b37; background: #eaddc4; }

/* 修改点2：将 bottom 距离调整至 8px，并且微调左右间距（left/right），使按钮距离输入框最底部边缘有呼吸感 */
.input-actions { position: absolute; bottom: 8px; left: 8px; right: 8px; display: flex; justify-content: space-between; align-items: center; }

.send-btn { padding: 4px 12px; background-color: #5c4b37; color: #fdfae9; border: none; border-radius: 4px; font-size: 12px; font-weight: bold; cursor: pointer; transition: all 0.2s; white-space: nowrap; }
.send-btn:hover:not(:disabled) { background-color: #4a3c2c; }
.send-btn:disabled { opacity: 0.6; cursor: not-allowed; }

.voice-record-btn { padding: 2px 6px; background: none; border: none; font-size: 16px; cursor: pointer; transition: all 0.2s; user-select: none; border-radius: 4px; }
.voice-record-btn:hover:not(:disabled) { background-color: #eaddc4; }
.voice-record-btn.recording { background-color: #bc423f; color: white; animation: pulse 1.5s infinite; }
@keyframes pulse { 0% { box-shadow: 0 0 0 0 rgba(188, 66, 63, 0.4); } 70% { box-shadow: 0 0 0 10px rgba(188, 66, 63, 0); } 100% { box-shadow: 0 0 0 0 rgba(188, 66, 63, 0); } }

/* 删除二次确认弹窗 */
.confirm-modal-overlay { position: absolute; top: 0; left: 0; right: 0; bottom: 0; background-color: rgba(92, 75, 55, 0.4); backdrop-filter: blur(2px); display: flex; justify-content: center; align-items: center; z-index: 100; }
.confirm-modal { background: #fdfae9; padding: 20px; border: 2px solid #d3c4a1; border-radius: 12px; box-shadow: 0 10px 20px rgba(0,0,0,0.1); text-align: center; width: 80%; }
.confirm-modal p { color: #5c4b37; font-weight: bold; margin: 0 0 20px 0; font-size: 14px; }
.confirm-preview { font-weight: normal !important; font-size: 13px !important; color: #8c7a65 !important; background: #fcf9ee; padding: 8px 12px; border-radius: 6px; border: 1px solid #eaddc4; margin-bottom: 16px !important; word-break: break-word; max-height: 80px; overflow-y: auto; }
.confirm-hint { font-weight: normal !important; font-size: 12px !important; color: #b5a992 !important; margin: 12px 0 0 0 !important; }
.confirm-actions { display: flex; gap: 10px; }
.confirm-btn { flex: 1; padding: 8px; border-radius: 6px; font-size: 13px; font-weight: bold; cursor: pointer; border: none; }
.confirm-btn.cancel { background: transparent; border: 1px solid #d3c4a1; color: #5c4b37; }
.confirm-btn.cancel:hover { background: #eaddc4; }
.confirm-btn.danger { background: #bc423f; color: white; }
.confirm-btn.danger:hover { background: #a13431; }
</style>