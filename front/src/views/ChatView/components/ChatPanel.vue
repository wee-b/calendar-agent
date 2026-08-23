<template>
  <div class="chat-page">
    <div class="chat-content" ref="sidebarContentRef">

      <div class="chat-header">
        <div class="session-selector" @click="toggleDropdown">
          <h2>
            {{ isUserLoggedIn ? (currentSessionTitle || '新对话') : '语音助手' }}
            <span v-if="isUserLoggedIn" class="arrow" :class="{ open: isDropdownOpen }">⌄</span>
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
          <button class="delete-btn" @click.stop="promptDelete(s.sessionId)" title="删除对话">×</button>
        </div>
        <div v-if="sessions.length === 0" class="empty-sessions">暂无历史对话</div>
      </div>

      <div class="chat-history" :class="{ 'has-messages': messages.length > 0 }" ref="chatHistoryRef">

        <div v-if="messages.length === 0" class="empty-chat">
          <h2>有什么我能帮你的吗？</h2>
          <div class="prompt-grid">
            <button>帮我规划明天的日程</button>
            <button>本周有哪些待办需要优先处理？</button>
            <button>安排一个复习计划</button>
            <button>帮我创建一个会议提醒</button>
            <button>总结今天的日程完成情况</button>
            <button>取消后天的日程</button>
          </div>
        </div>

        <div
            v-for="(msg, index) in messages"
            :key="index"
            class="msg-bubble"
            :class="isUserRole(msg.role) ? 'user-msg' : 'ai-msg'"
        >
          <span class="avatar">{{ isUserRole(msg.role) ? '我' : 'AI' }}</span>

          <div class="bubble-content">
            <div v-if="msg.thinking?.length" class="thinking-panel" :class="{ completed: msg.thinkingDone }">
              <button class="thinking-summary" @click="toggleThinking(index)">
                <span>{{ thinkingSummary(msg) }}</span>
                <span class="thinking-arrow" :class="{ open: !msg.thinkingCollapsed }">⌄</span>
              </button>
              <div v-if="!msg.thinkingCollapsed" class="thinking-steps">
                <div
                    v-for="(step, stepIndex) in msg.thinking"
                    :key="`${index}-${stepIndex}`"
                    class="thinking-step"
                    :class="{ done: msg.thinkingDone }"
                >
                  {{ step }}
                </div>
              </div>
            </div>
            <div v-else-if="!isUserRole(msg.role) && msg.responseTimeMs != null" class="response-time">
              耗时 {{ formatResponseTime(msg.responseTimeMs) }}
            </div>

            <p v-if="msg.content && isUserRole(msg.role)">{{ msg.content }}</p>
            <div
                v-else-if="msg.content"
                class="markdown-body"
                v-html="renderMarkdown(msg.content)"
            ></div>
            <div v-else-if="msg.loading" class="typing-indicator">
              <span></span><span></span><span></span>
            </div>

            <div class="msg-actions" v-if="!msg.loading && !isUserRole(msg.role)">
              <span class="action-icon" @click="handleCopy(msg.content)">复制</span>
              <span
                  class="action-icon"
                  :class="{ active: readingMsgIndex === index && !isPaused, paused: readingMsgIndex === index && isPaused }"
                  @click="handleRead(msg.content, index)"
              >{{ readingMsgIndex === index ? (isPaused ? '继续' : '暂停') : '朗读' }}</span>
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
              :placeholder="inputMudle === 2 ? (isRecording ? '正在聆听...' : '正在聆听，说完后说发送提交') : '发消息...'"
              @keydown.enter.exact.prevent="handleSend"
              :disabled="isSending || !isUserLoggedIn"
              :rows="isExpanded ? 10 : 3"
          ></textarea>
          <div class="input-actions">
            <div class="voice-controls" :class="{ 'is-voice-mode': inputMudle === 2 }">
              <button
                  class="voice-record-btn"
                  :class="{ recording: isRecording }"
                  @click="toggleVoice"
                  :disabled="isSending || !isUserLoggedIn"
              >
                {{ inputMudle === 2 ? '听' : '语' }}
              </button>
              <div class="decibel-meter">
                <div
                    class="db-segment"
                    v-for="i in 10"
                    :key="i"
                    :style="{ height: dbBars[i-1] + 'px' }"
                ></div>
              </div>
            </div>
            <button
                class="send-btn"
                @click="handleSend"
                :disabled="isSending || !inputText.trim() || !isUserLoggedIn"
            >
              发送
            </button>
          </div>
          <span class="expand-icon" @click="isExpanded = !isExpanded" :title="isExpanded ? '收起' : '展开'">
            {{ isExpanded ? '收起' : '展开' }}
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
          <p class="confirm-hint">也可以说“确认”或“取消”来控制</p>
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
  </div>
</template>

<script setup lang="ts">
import { ref, computed, nextTick, onMounted, onUnmounted, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import {
  newSessionAPI, streamChatAPI, getSessionsAPI, getHistoryAPI, deleteSessionAPI, deleteLastRoundAPI, type ChatSessionVO
} from '../../../api/chat';
import { tokenRef } from '../../../utils/auth';
import { renderMarkdown } from '../../../utils/markdown';

const emit = defineEmits<{
  (e: 'refresh'): void;
  (e: 'title-change', title: string): void;
}>();
const route = useRoute();
const router = useRouter();
const isUserLoggedIn = computed(() => !!tokenRef.value);

interface ChatMessage {
  role: string;
  content: string;
  loading?: boolean;
  thinking?: string[];
  thinkingCollapsed?: boolean;
  thinkingDone?: boolean;
  thinkingStartedAt?: number;
  thinkingFinishedAt?: number;
  responseTimeMs?: number | null;
}

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
const inputMudle = ref(1); // 1-鎵嬪姩锛?-璇煶
let recognition: any = null;
let confirmRecognition: any = null;
let sendGuard = false;
let pendingRouteSessionId: string | null = null;
let historyLoadToken = 0;
let voiceTimer: any = null;
let thinkingTimer: any = null;
const nowTime = ref(Date.now());
const VOICE_TIMEOUT = 10 * 60 * 1000; // 10鍒嗛挓
const dbBars = ref([4, 4, 4, 4, 4, 4, 4, 4, 4, 4]);
let audioContext: AudioContext | null = null;
let analyser: AnalyserNode | null = null;
let mediaStream: MediaStream | null = null;
let animFrameId: number | null = null;

const currentSessionTitle = computed(() => {
  const session = sessions.value.find(s => s.sessionId === currentSessionId.value);
  const firstUserMessage = messages.value.find(msg => isUserRole(msg.role) && msg.content.trim());
  const fallbackTitle = firstUserMessage?.content.trim();
  return session?.title || (fallbackTitle ? (fallbackTitle.length > 30 ? `${fallbackTitle.slice(0, 30)}...` : fallbackTitle) : '新对话');
});

const isUserRole = (role: string) => role.toLowerCase() === 'user';

const formatDuration = (durationMs: number) => {
  return `${(Math.max(0, durationMs) / 1000).toFixed(1)} 秒`;
};

const formatResponseTime = (durationMs: number) => {
  return `${Math.max(0.1, durationMs / 1000).toFixed(1)} 秒`;
};

const thinkingSummary = (msg: ChatMessage) => {
  if (msg.thinkingDone && msg.responseTimeMs != null) {
    return `耗时 ${formatResponseTime(msg.responseTimeMs)}`;
  }
  const start = msg.thinkingStartedAt || nowTime.value;
  const end = msg.thinkingFinishedAt || nowTime.value;
  return msg.thinkingDone ? `耗时 ${formatDuration(end - start)}` : `思考中 ${formatDuration(nowTime.value - start)}`;
};

const toggleThinking = (index: number) => {
  const msg = messages.value[index];
  if (msg?.thinking?.length) {
    msg.thinkingCollapsed = !msg.thinkingCollapsed;
  }
};

const completeThinkingStep = (step: string) => {
  return step
      .replace(/^已收到消息，正在准备处理\.\.\.$/, '已收到消息成功 √')
      .replace(/^已收到消息，正在理解你的需求\.\.\.$/, '已理解你的需求成功 √')
      .replace(/^正在(.+?)(?:\.\.\.)?$/, '已$1成功 √');
};

const completeThinking = (msg?: ChatMessage) => {
  if (!msg || !msg.thinking?.length) return;
  if (msg.thinkingDone) return;
  msg.thinking = msg.thinking.map(completeThinkingStep);
  msg.thinkingDone = true;
  msg.thinkingFinishedAt = Date.now();
};

const failThinking = (msg?: ChatMessage) => {
  if (!msg || !msg.thinking?.length || msg.thinkingDone) return;
  msg.thinking = msg.thinking.map(step => step.replace(/^正在(.+?)(?:\.\.\.)?$/, '$1失败'));
  msg.thinkingDone = true;
  msg.thinkingFinishedAt = Date.now();
};

const scrollToBottom = async () => {
  await nextTick();
  if (chatHistoryRef.value) {
    chatHistoryRef.value.scrollTop = chatHistoryRef.value.scrollHeight;
  }
};

const toggleDropdown = () => { if (isUserLoggedIn.value) isDropdownOpen.value = !isDropdownOpen.value; };

// ================= 浼氳瘽绠＄悊 =================
const sortSessionsByRecent = (list: ChatSessionVO[]) => {
  return [...list].sort((a, b) => new Date(b.createTime).getTime() - new Date(a.createTime).getTime());
};

const fetchSessions = async () => {
  if (!isUserLoggedIn.value) return;
  try {
    const res = await getSessionsAPI();
    sessions.value = sortSessionsByRecent(res || []);
  } catch (error) {}
};

const getRouteSessionId = () => {
  const id = route.query.sessionId;
  return Array.isArray(id) ? id[0] || null : id || null;
};

const loadSessionById = async (sessionId: string) => {
  const loadToken = ++historyLoadToken;
  currentSessionId.value = sessionId;
  messages.value = [];
  try {
    const history = await getHistoryAPI(sessionId);
    if (loadToken !== historyLoadToken || sessionId !== currentSessionId.value) return;
    messages.value = history.map(h => ({ role: h.role, content: h.content, responseTimeMs: h.responseTimeMs }));
    scrollToBottom();
  } catch (error) {}
};

const selectSession = async (session: ChatSessionVO) => {
  currentSessionId.value = session.sessionId;
  isDropdownOpen.value = false;
  messages.value = [];
  router.replace({ path: '/conversation', query: { sessionId: session.sessionId } });
  try {
    const history = await getHistoryAPI(session.sessionId);
    messages.value = history.map(h => ({ role: h.role, content: h.content, responseTimeMs: h.responseTimeMs }));
    scrollToBottom();
  } catch (error) {}
};

const createNewSession = async () => {
  if (!isUserLoggedIn.value) { ElMessage.warning('请先登录'); return; }
  isDropdownOpen.value = false;
  currentSessionId.value = null;
  messages.value = [];
  historyLoadToken++;
  await router.replace({ path: '/conversation' });
};

const startSessionForSend = async (title: string) => {
  try {
    const res = await newSessionAPI();
    currentSessionId.value = res.sessionId || Object.values(res)[0];
    if (!currentSessionId.value) return false;
    pendingRouteSessionId = currentSessionId.value;
    const displayTitle = title.length > 30 ? `${title.slice(0, 30)}...` : title;
    sessions.value = [
      { sessionId: currentSessionId.value, title: displayTitle || '新对话', createTime: new Date().toISOString(), messageCount: 0 },
      ...sessions.value.filter(s => s.sessionId !== currentSessionId.value)
    ];
    await router.replace({ path: '/conversation', query: { sessionId: currentSessionId.value } });
    return true;
  } catch (error) {}
  return false;
};

// ================= 鍒犻櫎鎿嶄綔 =================
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

// ================= 娑堟伅浜や簰锛氬鍒?/ 鏈楄 / 鎾ゅ洖 =================
const handleCopy = async (text: string) => {
  try {
    await navigator.clipboard.writeText(text);
    ElMessage.success('已复制到剪贴板');
  } catch (error) {
    ElMessage.error('复制失败，请手动复制');
  }
};

const isReading = ref(false);
const isPaused = ref(false);
const readingMsgIndex = ref<number | null>(null);

const handleRead = (text: string, msgIndex?: number) => {
  if ('speechSynthesis' in window) {
    if (msgIndex !== undefined && readingMsgIndex.value === msgIndex && isReading.value) {
      if (isPaused.value) {
        window.speechSynthesis.resume();
      } else {
        window.speechSynthesis.pause();
      }
      return;
    }
    window.speechSynthesis.cancel();
    const utterance = new SpeechSynthesisUtterance(text);
    utterance.lang = 'zh-CN';
    utterance.onstart = () => {
      isReading.value = true; isPaused.value = false;
      readingMsgIndex.value = msgIndex ?? null;
    };
    utterance.onend = () => { isReading.value = false; isPaused.value = false; readingMsgIndex.value = null; };
    utterance.onpause = () => { isPaused.value = true; };
    utterance.onresume = () => { isPaused.value = false; };
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
    messages.value = history.map(h => ({ role: h.role, content: h.content, responseTimeMs: h.responseTimeMs }));
    scrollToBottom();
  } catch (error) {}
};

// ================= 璇煶璇嗗埆褰曞叆 =================
const clearVoiceTimer = () => {
  if (voiceTimer) { clearTimeout(voiceTimer); voiceTimer = null; }
};

const resetVoiceTimer = () => {
  clearVoiceTimer();
  voiceTimer = setTimeout(() => {
    inputMudle.value = 1;
    stopVoice();
    cleanupConfirmRecognition();
  }, VOICE_TIMEOUT);
};

const toggleVoice = () => {
  if (inputMudle.value === 2) {
    inputMudle.value = 1;
    clearVoiceTimer();
    stopVoice();
    cleanupConfirmRecognition();
    window.speechSynthesis.cancel();
    isReading.value = false;
    isPaused.value = false;
  } else {
    inputMudle.value = 2;
    resetVoiceTimer();
    startVoice();
  }
};

const startDbMeter = async () => {
  stopDbMeter();
  try {
    mediaStream = await navigator.mediaDevices.getUserMedia({ audio: true });
    audioContext = new AudioContext();
    analyser = audioContext.createAnalyser();
    analyser.fftSize = 256;
    const source = audioContext.createMediaStreamSource(mediaStream);
    source.connect(analyser);

    const bufferLength = analyser.frequencyBinCount;
    const dataArray = new Uint8Array(bufferLength);

    const tick = () => {
      if (!analyser) return;
      analyser.getByteFrequencyData(dataArray);
      const bands = [0, 3, 7, 12, 18, 26, 36, 48, 63, 82, bufferLength];
      dbBars.value = Array.from({ length: 10 }, (_, i) => {
        let sum = 0;
        const start = bands[i], end = bands[i + 1];
        for (let j = start; j < end; j++) sum += dataArray[j];
        const avg = sum / (end - start);
        return Math.max(4, Math.round(avg * 0.22));
      });
      animFrameId = requestAnimationFrame(tick);
    };
    animFrameId = requestAnimationFrame(tick);
  } catch (e) {
    audioContext = null;
    analyser = null;
    mediaStream = null;
    const fallback = () => {
      dbBars.value = Array.from({ length: 10 }, () => Math.floor(Math.random() * 16) + 4);
      animFrameId = requestAnimationFrame(fallback);
    };
    animFrameId = requestAnimationFrame(fallback);
  }
};

const stopDbMeter = () => {
  if (animFrameId !== null) { cancelAnimationFrame(animFrameId); animFrameId = null; }
  if (audioContext) { audioContext.close(); audioContext = null; }
  if (mediaStream) { mediaStream.getTracks().forEach(t => t.stop()); mediaStream = null; }
  analyser = null;
  dbBars.value = [4, 4, 4, 4, 4, 4, 4, 4, 4, 4];
};

const startVoice = () => {
  if (!isUserLoggedIn.value || inputMudle.value !== 2) return;

  const SpeechRecognition = (window as any).SpeechRecognition || (window as any).webkitSpeechRecognition;
  if (!SpeechRecognition) {
    ElMessage.warning('当前浏览器不支持语音识别');
    inputMudle.value = 1;
    return;
  }

  try { if (recognition) recognition.stop(); } catch {}

  recognition = new SpeechRecognition();
  recognition.lang = 'zh-CN';
  recognition.interimResults = true;
  recognition.continuous = true;

  isRecording.value = true;
  sendGuard = false;
  startDbMeter();

  recognition.onresult = (event: any) => {
    let currentText = '';
    for (let i = 0; i < event.results.length; i++) {
      currentText += event.results[i][0].transcript;
    }
    inputText.value = currentText.replace(/[。！？，“”、；：\)\}】』」）]+$/g, '').trim();
  };

  recognition.onerror = () => {
    isRecording.value = false;
  };

  recognition.onend = () => {
    isRecording.value = false;
    if (inputMudle.value === 2 && recognition !== null) {
      setTimeout(() => startVoice(), 300);
    }
  };

  recognition.start();
};

const stopVoice = () => {
  if (recognition) {
    try { recognition.stop(); } catch {}
    recognition = null;
  }
  isRecording.value = false;
  stopDbMeter();
};

// ================= 璇煶鍙戦€佺‘璁?=================
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
  window.speechSynthesis.cancel();
  isReading.value = false;
  isPaused.value = false;
  confirmSendVisible.value = true;
  startConfirmRecognition();
});

// ================= 鍙戦€佹秷鎭?=================
const CHAT_TIMEOUT = 30000;

const handleSend = async () => {
  let text = inputText.value.trim();
  text = text.replace(/发送[。！？，“”、；：\s]*$/g, '').trim();
  if (!text || isSending.value || !isUserLoggedIn.value) return;

  isSending.value = true;
  if (!currentSessionId.value) {
    const started = await startSessionForSend(text);
    if (!started) {
      isSending.value = false;
      return;
    }
  }
  const activeSessionId = currentSessionId.value;
  if (!activeSessionId) {
    isSending.value = false;
    return;
  }

  stopVoice();
  messages.value.push({ role: 'user', content: text });
  inputText.value = '';
  scrollToBottom();

  messages.value.push({
    role: 'ai',
    content: '',
    loading: true,
    thinking: ['已收到消息，正在准备处理...'],
    thinkingCollapsed: false,
    thinkingStartedAt: Date.now()
  });
  scrollToBottom();

  let slowTimer: any = null;
  try {
    slowTimer = setTimeout(() => {
      ElMessage.warning('响应较慢，请耐心等待...');
    }, CHAT_TIMEOUT);

    await streamChatAPI(
      { sessionId: activeSessionId, message: text },
      (token) => {
        clearTimeout(slowTimer);
        const lastMsg = messages.value[messages.value.length - 1];
        if (lastMsg && lastMsg.role === 'ai') {
          completeThinking(lastMsg);
          lastMsg.thinkingCollapsed = true;
          if (lastMsg.loading) lastMsg.loading = false;
          lastMsg.content += token;
          scrollToBottom();
        }
      },
      () => {
        emit('refresh');
        const lastMsg = messages.value[messages.value.length - 1];
        completeThinking(lastMsg);
        if (lastMsg && lastMsg.role === 'ai' && inputMudle.value === 2) {
          handleRead(lastMsg.content, messages.value.length - 1);
        }
        fetchSessions();
      },
      (error) => {
        clearTimeout(slowTimer);
        const lastMsg = messages.value[messages.value.length - 1];
        if (lastMsg && lastMsg.loading) {
          failThinking(lastMsg);
          lastMsg.thinkingCollapsed = false;
          lastMsg.loading = false;
          lastMsg.content = error || '抱歉，网络开小差了，请重试。';
        }
      },
      () => {
        emit('refresh');
        fetchSessions();
      },
      (progress) => {
        const lastMsg = messages.value[messages.value.length - 1];
        if (lastMsg && lastMsg.role === 'ai') {
          if (!lastMsg.thinking) lastMsg.thinking = [];
          if (lastMsg.thinking[lastMsg.thinking.length - 1] !== progress) {
            lastMsg.thinking.push(progress);
          }
          scrollToBottom();
        }
      },
      (responseTimeMs) => {
        const lastMsg = messages.value[messages.value.length - 1];
        if (lastMsg && lastMsg.role === 'ai') {
          lastMsg.responseTimeMs = responseTimeMs;
          completeThinking(lastMsg);
          lastMsg.thinkingCollapsed = true;
          scrollToBottom();
        }
      }
    );
  } finally {
    isSending.value = false;
    scrollToBottom();
    inputText.value = '';
    if (inputMudle.value === 2) {
      resetVoiceTimer();
      setTimeout(() => startVoice(), 400);
    }
    if (inputMudle.value === 1) {
      nextTick(() => {
        const ta = document.querySelector('.chat-input') as HTMLTextAreaElement;
        if (ta) ta.focus();
      });
    }
  }
};

// ================= 鐢熷懡鍛ㄦ湡 =================
onMounted(async () => {
  thinkingTimer = setInterval(() => {
    nowTime.value = Date.now();
  }, 1000);
  if (isUserLoggedIn.value) {
    await fetchSessions();
    const routeSessionId = getRouteSessionId();
    if (routeSessionId) {
      await loadSessionById(routeSessionId);
    } else {
      currentSessionId.value = null;
      messages.value = [];
    }
  }
});

onUnmounted(() => {
  if (thinkingTimer) clearInterval(thinkingTimer);
});

watch(isUserLoggedIn, async (newVal) => {
  if (newVal) {
    await fetchSessions();
    const routeSessionId = getRouteSessionId();
    if (routeSessionId) {
      await loadSessionById(routeSessionId);
    } else {
      currentSessionId.value = null;
      messages.value = [];
    }
    inputMudle.value = 1;
  } else {
    clearVoiceTimer();
    stopVoice();
    sessions.value = [];
    messages.value = [];
    currentSessionId.value = null;
  }
});

watch(
  () => route.query.sessionId,
  async () => {
    if (!isUserLoggedIn.value) return;
    const routeSessionId = getRouteSessionId();
    if (routeSessionId) {
      if (routeSessionId === pendingRouteSessionId) {
        pendingRouteSessionId = null;
        currentSessionId.value = routeSessionId;
        return;
      }
      await loadSessionById(routeSessionId);
    } else {
      historyLoadToken++;
      currentSessionId.value = null;
      messages.value = [];
    }
  }
);

watch(currentSessionTitle, (title) => {
  emit('title-change', title);
}, { immediate: true });
</script>

<style scoped>
/* 鍏ㄩ〉鑱婂ぉ鍖哄煙 */
.chat-page {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background-color: #fdfae9;
}

.chat-content {
  position: relative;
  width: 100%;
  max-width: 800px;
  margin: 0 auto;
  padding: 20px 24px 0;
  height: 100%;
  box-sizing: border-box;
  display: flex;
  flex-direction: column;
}

/* 澶撮儴 */
.chat-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; border-bottom: 2px dashed #d3c4a1; padding-bottom: 12px; }
.session-selector { flex: 1; cursor: pointer; overflow: hidden; user-select: none; }
.session-selector h2 { color: #5c4b37; font-size: 1.1rem; margin: 0; display: flex; align-items: center; gap: 6px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.arrow { font-size: 10px; color: #b5a992; transition: transform 0.2s; }
.arrow.open { transform: rotate(180deg); }
.new-chat-btn { background: none; border: 1px solid #d3c4a1; color: #5c4b37; font-size: 13px; font-weight: bold; padding: 4px 10px; border-radius: 4px; cursor: pointer; transition: all 0.2s; white-space: nowrap; margin-left: 10px; }
.new-chat-btn:hover { background: #eaddc4; }

/* 涓嬫媺鑿滃崟 */
.dropdown-overlay { position: absolute; top: 56px; left: 0; right: 0; bottom: 0; z-index: 10; }
.session-dropdown { position: absolute; top: 56px; left: 24px; right: 24px; max-width: 752px; margin: 0 auto; background: #fdfae9; border: 2px solid #d3c4a1; border-radius: 8px; box-shadow: 0 8px 24px rgba(92, 75, 55, 0.15); max-height: 250px; overflow-y: auto; z-index: 11; }
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

/* 鑱婂ぉ鍘嗗彶鍖?*/
.chat-history { flex: 1; overflow-y: auto; display: flex; flex-direction: column; gap: 16px; padding-right: 5px; padding-bottom: 20px; }
.chat-history::-webkit-scrollbar { width: 4px; }
.chat-history::-webkit-scrollbar-thumb { background: #d3c4a1; border-radius: 2px; }
.empty-chat { display: flex; flex-direction: column; align-items: center; justify-content: center; height: 100%; color: #b5a992; text-align: center; }
.empty-avatar { width: 48px; height: 48px; background: #eaddc4; border-radius: 50%; display: flex; align-items: center; justify-content: center; font-weight: bold; color: #5c4b37; font-size: 18px; margin-bottom: 16px; }
.empty-chat p { margin: 0; font-size: 13px; line-height: 1.6; }

/* 瀵硅瘽姘旀场 */
.msg-bubble { display: flex; flex-direction: column; gap: 4px; max-width: 90%; }
.avatar { font-size: 12px; color: #b5a992; font-weight: bold; }
.bubble-content { border-radius: 12px; padding: 10px 14px; box-shadow: 0 2px 6px rgba(92, 75, 55, 0.05); }
.bubble-content p { margin: 0; font-size: 14px; line-height: 1.5; color: #5c4b37; word-break: break-word; white-space: pre-wrap; }
.markdown-body {
  color: #344054;
  font-size: 14px;
  line-height: 1.65;
  word-break: break-word;
}
.markdown-body :deep(*) {
  box-sizing: border-box;
}
.markdown-body :deep(p),
.markdown-body :deep(ul),
.markdown-body :deep(ol),
.markdown-body :deep(pre),
.markdown-body :deep(blockquote),
.markdown-body :deep(table) {
  margin: 0 0 8px;
}
.markdown-body :deep(:last-child) {
  margin-bottom: 0;
}
.markdown-body :deep(ul),
.markdown-body :deep(ol) {
  padding-left: 20px;
}
.markdown-body :deep(li + li) {
  margin-top: 4px;
}
.markdown-body :deep(strong) {
  font-weight: 700;
  color: #101828;
}
.markdown-body :deep(code) {
  padding: 1px 5px;
  border-radius: 4px;
  background: #f2f4f7;
  color: #344054;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 0.92em;
}
.markdown-body :deep(pre) {
  overflow-x: auto;
  padding: 10px 12px;
  border-radius: 8px;
  background: #101828;
  color: #f8fafc;
}
.markdown-body :deep(pre code) {
  padding: 0;
  background: transparent;
  color: inherit;
  white-space: pre;
}
.markdown-body :deep(a) {
  color: #2563eb;
  text-decoration: none;
}
.markdown-body :deep(a:hover) {
  text-decoration: underline;
}
.markdown-body :deep(table) {
  display: block;
  width: max-content;
  max-width: 100%;
  overflow-x: auto;
  border-collapse: collapse;
  border: 1px solid #d0d5dd;
  border-radius: 8px;
  background: #ffffff;
}
.markdown-body :deep(th),
.markdown-body :deep(td) {
  min-width: 88px;
  padding: 8px 10px;
  border: 1px solid #d0d5dd;
  text-align: left;
  vertical-align: top;
  white-space: normal;
}
.markdown-body :deep(th) {
  background: #f2f4f7;
  color: #101828;
  font-weight: 700;
}
.markdown-body :deep(tr:nth-child(even) td) {
  background: #f8fafc;
}

.user-msg { align-self: flex-end; align-items: flex-end; }
.user-msg .bubble-content { background-color: #eaddc4; border-bottom-right-radius: 2px; }
.ai-msg { align-self: flex-start; align-items: flex-start; }
.ai-msg .bubble-content { background-color: #fcf9ee; border: 1px solid #d3c4a1; border-bottom-left-radius: 2px; }

.thinking-panel {
  margin-bottom: 14px;
}

.thinking-summary {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 0;
  border: 0;
  background: transparent;
  color: #667085;
  font-size: 14px;
  line-height: 1.4;
  cursor: pointer;
  font-family: inherit;
}

.thinking-arrow {
  display: inline-block;
  color: #98a2b3;
  font-size: 14px;
  transform: rotate(-90deg);
  transition: transform 0.16s ease;
}

.thinking-arrow.open {
  transform: rotate(0deg);
}

.thinking-steps {
  margin-top: 10px;
  padding-top: 12px;
  border-top: 1px solid #e4e7ec;
}

.thinking-step {
  position: relative;
  padding-left: 20px;
  color: #667085;
  font-size: 14px;
  line-height: 1.7;
  word-break: break-word;
}

.thinking-step::before {
  content: "";
  position: absolute;
  left: 0;
  top: 9px;
  width: 12px;
  height: 12px;
  color: #2563eb;
  font-size: 12px;
  line-height: 12px;
  text-align: center;
}

.thinking-step:not(.done)::before {
  width: 6px;
  height: 6px;
  top: 10px;
  left: 3px;
  border-radius: 50%;
  background: #2563eb;
}

.thinking-step.done::before {
  content: "√";
  color: #16a34a;
  font-weight: 800;
}

.thinking-panel.completed .thinking-summary {
  color: #667085;
}

.response-time {
  margin-bottom: 10px;
  color: #667085;
  font-size: 14px;
  line-height: 1.4;
}

/* 姘旀场宸ュ叿鏍?*/
.msg-actions { display: flex; gap: 16px; margin-top: 10px; padding-top: 8px; border-top: 1px dashed #eaddc4; }
.action-icon { font-size: 12px; font-weight: bold; color: #b5a992; cursor: pointer; transition: color 0.2s; }
.action-icon:hover { color: #5c4b37; text-decoration: underline; }
.action-icon.danger:hover { color: #bc423f; }

/* 鍔犺浇鍔ㄧ敾 */
.typing-indicator { display: flex; gap: 4px; align-items: center; height: 20px; }
.typing-indicator span { width: 6px; height: 6px; background-color: #b5a992; border-radius: 50%; animation: typing 1.4s infinite ease-in-out both; }
.typing-indicator span:nth-child(1) { animation-delay: -0.32s; }
.typing-indicator span:nth-child(2) { animation-delay: -0.16s; }
@keyframes typing { 0%, 80%, 100% { transform: scale(0); } 40% { transform: scale(1); background-color: #5c4b37; } }

/* 杈撳叆鍖哄煙 */
.chat-input-area { padding: 10px 0; border-top: 1px solid #eaddc4; background-color: #fdfae9; z-index: 2; flex-shrink: 0; }
.textarea-wrapper { position: relative; }
.chat-input { width: 100%; padding: 8px 32px 42px 12px; background-color: #fcf9ee; border: 1px solid #d3c4a1; border-radius: 6px; font-size: 13px; color: #5c4b37; outline: none; transition: border-color 0.2s; line-height: 1.5; resize: none; box-sizing: border-box; font-family: inherit; }
.chat-input:focus { border-color: #5c4b37; }
.chat-input:disabled { background-color: #f2ecd9; cursor: not-allowed; }
.expand-icon { position: absolute; top: 8px; right: 8px; font-size: 13px; color: #b5a992; cursor: pointer; padding: 2px 6px; border-radius: 3px; transition: all 0.2s; user-select: none; z-index: 1; }
.expand-icon:hover { color: #5c4b37; background: #eaddc4; }
.input-actions { position: absolute; bottom: 8px; left: 8px; right: 8px; display: flex; justify-content: space-between; align-items: center; }
.send-btn { padding: 4px 12px; background-color: #5c4b37; color: #fdfae9; border: none; border-radius: 4px; font-size: 12px; font-weight: bold; cursor: pointer; transition: all 0.2s; white-space: nowrap; }
.send-btn:hover:not(:disabled) { background-color: #4a3c2c; }
.send-btn:disabled { opacity: 0.6; cursor: not-allowed; }

.voice-controls { display: flex; align-items: center; }
.voice-record-btn { padding: 2px 6px; background: none; border: none; font-size: 16px; cursor: pointer; transition: all 0.2s; user-select: none; border-radius: 4px; flex-shrink: 0; }
.voice-record-btn:hover:not(:disabled) { background-color: #eaddc4; }
.voice-record-btn.recording { background-color: #bc423f; color: white; animation: pulse 1.5s infinite; }
.read-toggle-btn { padding: 2px 5px; background: none; border: none; font-size: 13px; cursor: pointer; transition: all 0.2s; user-select: none; border-radius: 4px; flex-shrink: 0; color: #b5a992; }
.read-toggle-btn:hover { background-color: #eaddc4; color: #5c4b37; }
.read-toggle-btn.active { color: #5c4b37; }
.read-toggle-btn.paused { color: #bc423f; animation: pulse 2s infinite; }
@keyframes pulse { 0% { box-shadow: 0 0 0 0 rgba(188, 66, 63, 0.4); } 70% { box-shadow: 0 0 0 10px rgba(188, 66, 63, 0); } 100% { box-shadow: 0 0 0 0 rgba(188, 66, 63, 0); } }
.decibel-meter { display: flex; align-items: flex-end; gap: 3px; height: 22px; max-width: 0; overflow: hidden; transition: max-width 0.35s cubic-bezier(0.4, 0, 0.2, 1); background: rgba(92, 75, 55, 0.06); border-radius: 4px; padding: 0; }
.is-voice-mode .decibel-meter { max-width: 90px; padding: 3px 6px; }
.db-segment { width: 3px; min-width: 3px; background: #d3c4a1; border-radius: 1.5px; transition: height 0.15s ease, background-color 0.15s ease; }
.is-voice-mode .db-segment { background: #5c4b37; }

/* 纭寮圭獥 */
.confirm-modal-overlay { position: absolute; top: 0; left: 0; right: 0; bottom: 0; background-color: rgba(92, 75, 55, 0.4); backdrop-filter: blur(2px); display: flex; justify-content: center; align-items: center; z-index: 100; }
.confirm-modal { background: #fdfae9; padding: 20px; border: 2px solid #d3c4a1; border-radius: 12px; box-shadow: 0 10px 20px rgba(0,0,0,0.1); text-align: center; width: 80%; max-width: 400px; }
.confirm-modal p { color: #5c4b37; font-weight: bold; margin: 0 0 20px 0; font-size: 14px; }
.confirm-preview { font-weight: normal !important; font-size: 13px !important; color: #8c7a65 !important; background: #fcf9ee; padding: 8px 12px; border-radius: 6px; border: 1px solid #eaddc4; margin-bottom: 16px !important; word-break: break-word; max-height: 80px; overflow-y: auto; }
.confirm-hint { font-weight: normal !important; font-size: 12px !important; color: #b5a992 !important; margin: 12px 0 0 0 !important; }
.confirm-actions { display: flex; gap: 10px; }
.confirm-btn { flex: 1; padding: 8px; border-radius: 6px; font-size: 13px; font-weight: bold; cursor: pointer; border: none; }
.confirm-btn.cancel { background: transparent; border: 1px solid #d3c4a1; color: #5c4b37; }
.confirm-btn.cancel:hover { background: #eaddc4; }
.confirm-btn.danger { background: #bc423f; color: white; }
.confirm-btn.danger:hover { background: #a13431; }

/* Modern full-page agent chat refresh */
.chat-page {
  background: #f8fafc;
}

.chat-content {
  max-width: 920px;
  padding: 26px 28px 0;
}

.chat-header {
  border: 1px solid #e4e7ec;
  border-radius: 12px;
  padding: 14px 16px;
  margin-bottom: 18px;
  background: #ffffff;
  box-shadow: 0 14px 36px rgba(15, 23, 42, 0.06);
}

.session-selector h2 {
  color: #101828;
  font-size: 17px;
  font-weight: 780;
}

.new-chat-btn {
  border: 1px solid rgba(37, 99, 235, 0.2);
  background: #eff6ff;
  color: #1d4ed8;
  border-radius: 8px;
  padding: 8px 12px;
}

.new-chat-btn:hover {
  background: #dbeafe;
}

.session-dropdown {
  background: #ffffff;
  border: 1px solid rgba(15, 23, 42, 0.1);
  box-shadow: 0 18px 45px rgba(15, 23, 42, 0.14);
}

.session-item {
  border-bottom: 1px solid #eef2f7;
}

.session-item:hover {
  background: #f8fafc;
}

.session-item.active {
  background: #eff6ff;
}

.session-name {
  color: #344054;
}

.empty-avatar {
  width: 56px;
  height: 56px;
  border-radius: 14px;
  background: linear-gradient(135deg, #2563eb, #0891b2);
  color: #ffffff;
}

.empty-chat {
  color: #667085;
}

.bubble-content {
  border-radius: 12px;
  box-shadow: none;
}

.bubble-content p {
  color: #344054;
}

.user-msg .bubble-content {
  background: #2563eb;
}

.user-msg .bubble-content p {
  color: #ffffff;
}

.ai-msg .bubble-content {
  background: #ffffff;
  border: 1px solid #e4e7ec;
}

.avatar {
  color: #667085;
}

.msg-actions {
  border-top: 1px solid #e4e7ec;
}

.action-icon {
  color: #667085;
}

.action-icon:hover {
  color: #2563eb;
}

.chat-input-area {
  border-top: 0;
  background: #f8fafc;
  padding: 12px 0 18px;
}

.chat-input {
  background: #ffffff;
  border: 1px solid #d0d5dd;
  border-radius: 12px;
  color: #101828;
  box-shadow: 0 14px 36px rgba(15, 23, 42, 0.08);
}

.chat-input:focus {
  border-color: #2563eb;
  box-shadow: 0 0 0 3px rgba(37, 99, 235, 0.12), 0 14px 36px rgba(15, 23, 42, 0.08);
}

.send-btn {
  background: #2563eb;
  border-radius: 8px;
  color: #ffffff;
}

.send-btn:hover:not(:disabled) {
  background: #1d4ed8;
}

.voice-record-btn:hover:not(:disabled),
.expand-icon:hover {
  background: #eff6ff;
  color: #2563eb;
}

.confirm-modal-overlay {
  background-color: rgba(15, 23, 42, 0.46);
}

.confirm-modal {
  background: #ffffff;
  border: 1px solid rgba(15, 23, 42, 0.1);
}

.confirm-modal p {
  color: #101828;
}

/* Doubao-like client layout refinement */
.chat-page {
  background: #ffffff;
}

.chat-content {
  max-width: none;
  width: 100%;
  padding: 0;
}

.chat-header {
  height: 66px;
  margin: 0;
  padding: 0 22px;
  display: grid;
  grid-template-columns: 1fr auto 1fr;
  align-items: center;
  border: 0;
  border-bottom: 1px solid #eeeeef;
  border-radius: 0;
  box-shadow: none;
  background: #ffffff;
}

.session-selector {
  grid-column: 2;
  text-align: center;
}

.session-selector h2 {
  justify-content: center;
  color: #18181b;
  font-size: 17px;
  font-weight: 650;
}

.session-selector h2::after {
  content: "AI 生成可能有误 注意核实";
  position: absolute;
  left: 50%;
  top: 38px;
  transform: translateX(-50%);
  color: #d4d4d8;
  font-size: 12px;
  font-weight: 400;
  white-space: nowrap;
}

.new-chat-btn {
  grid-column: 3;
  justify-self: end;
  background: #ffffff;
  border: 1px solid #dedee3;
  color: #18181b;
  border-radius: 20px;
  padding: 8px 18px;
  box-shadow: none;
}

.new-chat-btn:hover {
  background: #f4f4f5;
}

.chat-history {
  padding: 0 32px 150px;
}

.empty-chat {
  min-height: calc(100vh - 216px);
  height: auto;
  justify-content: center;
  color: #18181b;
}

.empty-chat h2 {
  margin: 0 0 34px;
  color: #030712;
  font-size: 34px;
  line-height: 1.2;
  font-weight: 800;
  letter-spacing: 0;
}

.prompt-grid {
  width: min(1040px, 78vw);
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 12px 10px;
}

.prompt-grid button {
  height: 50px;
  padding: 0 20px;
  border: 0;
  border-radius: 14px;
  background: #f4f4f5;
  color: #18181b;
  font-size: 16px;
  cursor: pointer;
  transition: background 0.16s, transform 0.16s;
}

.prompt-grid button:hover {
  background: #ececef;
  transform: translateY(-1px);
}

.chat-input-area {
  position: absolute;
  left: 50%;
  right: auto;
  bottom: 20px;
  width: min(1200px, calc(100% - 160px));
  transform: translateX(-50%);
  padding: 0;
  background: transparent;
}

.textarea-wrapper {
  min-height: 118px;
  padding: 18px 20px 14px;
  background: #ffffff;
  border: 1px solid #ececef;
  border-radius: 28px;
  box-shadow: 0 16px 46px rgba(15, 23, 42, 0.12);
}

.chat-input {
  min-height: 44px;
  padding: 0 40px 44px 0;
  border: 0;
  border-radius: 0;
  box-shadow: none;
  color: #18181b;
  font-size: 17px;
}

.chat-input:focus {
  border: 0;
  box-shadow: none;
}

.input-actions {
  left: 18px;
  right: 16px;
  bottom: 14px;
}

.send-btn {
  min-width: 58px;
  height: 34px;
  padding: 0 16px;
  border-radius: 999px;
  background: #18181b;
  font-size: 14px;
}

.send-btn:hover:not(:disabled) {
  background: #000000;
}

.voice-record-btn {
  width: 34px;
  height: 34px;
  border-radius: 50%;
  background: #f4f4f5;
}

.expand-icon {
  display: none;
}

@media (max-width: 900px) {
  .chat-input-area {
    width: calc(100% - 32px);
  }

  .prompt-grid {
    width: calc(100vw - 80px);
  }

  .empty-chat h2 {
    font-size: 28px;
  }
}

/* Redline polish: prevent overlap and tighten the shell proportions. */
.chat-header {
  height: 78px;
}

.session-selector h2 {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  line-height: 1.25;
  overflow: visible;
}

.session-selector h2 .arrow {
  position: absolute;
  right: -16px;
  top: 3px;
}

.session-selector h2::after {
  position: static;
  transform: none;
  font-size: 12px;
  line-height: 1.2;
}

.chat-history {
  padding: 22px 32px 250px;
  scroll-padding-bottom: 250px;
}

.msg-bubble {
  max-width: min(760px, 68%);
}

.chat-input-area {
  bottom: 24px;
  width: min(1040px, calc(100% - 220px));
}

.textarea-wrapper {
  min-height: 112px;
}

@media (max-width: 1100px) {
  .chat-input-area {
    width: calc(100% - 48px);
  }

  .msg-bubble {
    max-width: 86%;
  }
}

/* Logout and compact-window fixes. */
.chat-header {
  display: none;
}

.chat-page,
.chat-content {
  overflow: hidden;
}

.chat-history {
  min-height: 0;
  overflow-x: hidden;
  overflow-y: hidden;
}

.chat-history.has-messages {
  overflow-y: auto;
}

.empty-chat {
  min-height: 0;
  height: 100%;
  padding: 0 28px;
}

.prompt-grid {
  width: min(780px, 100%);
  max-width: 100%;
}

.prompt-grid button {
  max-width: 100%;
  white-space: nowrap;
}

.chat-input:disabled {
  background: #ffffff;
  cursor: not-allowed;
  opacity: 1;
}

.chat-input-area {
  max-width: calc(100% - 48px);
}

@media (max-width: 960px) {
  .chat-header {
    height: 84px;
    padding: 0 16px;
    grid-template-columns: 44px minmax(0, 1fr) auto;
  }

  .session-selector {
    grid-column: 2;
    min-width: 0;
  }

  .new-chat-btn {
    grid-column: 3;
    padding: 7px 14px;
  }

  .empty-chat h2 {
    font-size: 30px;
    margin-bottom: 28px;
  }

  .prompt-grid {
    gap: 10px;
  }

  .prompt-grid button {
    height: 46px;
    padding: 0 18px;
    font-size: 16px;
  }

  .chat-input-area {
    bottom: 18px;
    width: calc(100% - 42px);
  }

  .textarea-wrapper {
    min-height: 112px;
    border-radius: 24px;
  }
}

@media (max-height: 760px) {
  .chat-history {
    padding-top: 8px;
    padding-bottom: 190px;
    scroll-padding-bottom: 190px;
  }

  .empty-chat h2 {
    font-size: 28px;
    margin-bottom: 20px;
  }

  .prompt-grid button {
    height: 42px;
  }

  .textarea-wrapper {
    min-height: 96px;
  }
}
</style>

