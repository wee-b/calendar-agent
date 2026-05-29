<template>
  <aside class="sidebar right-sidebar" :class="{ 'is-collapsed': !isOpen }">
    <div class="sidebar-content">
      <h2>语音助手</h2>

      <div class="chat-history">
        <div class="msg-bubble user-msg">
          <span class="avatar">我</span>
          <p>"帮我把周三的会议取消"</p>
        </div>
        <div class="msg-bubble ai-msg">
          <span class="avatar">AI</span>
          <p>好的，已为您取消周三下午的会议，并在日历上清除了该日程。</p>
        </div>
      </div>

      <div class="mic-button-area">
        <button class="mic-btn" @mousedown="startRecord" @mouseup="stopRecord">
          <span class="mic-icon">🎙️</span> 按住说话
        </button>
      </div>
    </div>
  </aside>
</template>

<script setup lang="ts">
// 接收父组件传来的展开/收起状态
defineProps<{
  isOpen: boolean;
}>();

// 预留的语音交互逻辑接口
const startRecord = () => {
  console.log('开始录音...');
  // TODO: 接入浏览器麦克风 API
};

const stopRecord = () => {
  console.log('停止录音，发送请求...');
  // TODO: 发送音频到后端进行识别和意图提取
};
</script>

<style scoped>
/* 侧边栏基础结构 (与左侧保持对称) */
.sidebar {
  width: 20%;
  min-width: 280px;
  max-width: 400px;
  background-color: #fdfae9;
  transition: all 0.3s cubic-bezier(0.25, 0.8, 0.25, 1);
  display: flex;
  flex-direction: column;
  z-index: 5;
  overflow: hidden;
}

/* 右侧专属边框色 */
.right-sidebar {
  border-left: 2px solid #d3c4a1;
}

.sidebar-content {
  width: 100%;
  padding: 20px;
  min-width: 280px;
  height: 100%;
  box-sizing: border-box;
  display: flex;
  flex-direction: column;
}

/* 收起状态 */
.sidebar.is-collapsed {
  width: 0 !important;
  min-width: 0 !important;
  border: none;
}

/* --- 内部对话框复古排版 --- */
h2 {
  color: #5c4b37;
  font-size: 1.2rem;
  margin-top: 0;
  margin-bottom: 20px;
  border-bottom: 2px dashed #d3c4a1;
  padding-bottom: 10px;
}

.chat-history {
  flex: 1;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding-right: 5px; /* 给滚动条留点位置 */
}

/* 对话气泡通用样式 */
.msg-bubble {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.avatar {
  font-size: 12px;
  color: #b5a992;
  font-weight: bold;
}

.msg-bubble p {
  margin: 0;
  padding: 10px 14px;
  border-radius: 8px;
  font-size: 14px;
  line-height: 1.5;
  color: #5c4b37;
  box-shadow: 0 2px 4px rgba(92, 75, 55, 0.05);
}

/* 用户气泡（靠右） */
.user-msg {
  align-items: flex-end;
}
.user-msg p {
  background-color: #eaddc4; /* 偏深的卡其色 */
  border-bottom-right-radius: 2px;
}

/* AI气泡（靠左） */
.ai-msg {
  align-items: flex-start;
}
.ai-msg p {
  background-color: #fcf9ee; /* 极浅的底色 */
  border: 1px solid #eaddc4;
  border-bottom-left-radius: 2px;
}

/* 底部麦克风按钮 */
.mic-button-area {
  margin-top: 20px;
  padding-top: 20px;
  border-top: 1px solid #eaddc4;
}

.mic-btn {
  width: 100%;
  padding: 14px;
  background-color: #5c4b37; /* 深褐色 */
  color: #fdfae9;
  border: none;
  border-radius: 8px;
  font-size: 16px;
  font-weight: bold;
  cursor: pointer;
  transition: all 0.2s;
  display: flex;
  justify-content: center;
  align-items: center;
  gap: 8px;
  box-shadow: 0 4px 6px rgba(92, 75, 55, 0.2);
}

.mic-btn:hover {
  background-color: #4a3c2c;
  transform: translateY(-1px);
}

.mic-btn:active {
  background-color: #3b2f23;
  transform: translateY(1px);
  box-shadow: 0 1px 2px rgba(92, 75, 55, 0.2);
}
</style>