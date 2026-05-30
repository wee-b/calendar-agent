<template>
  <teleport to="body">
    <transition name="fade">
      <div v-if="visible" class="help-overlay" @click="$emit('close')"></div>
    </transition>
    <transition name="slide-up">
      <div v-if="visible" class="help-panel">
        <div class="help-header">
          <h2>语音助手使用指南</h2>
          <button class="help-close" @click="$emit('close')">✕</button>
        </div>
        <div class="help-body">
          <section class="help-section">
            <h3>语音输入</h3>
            <ul>
              <li>语音识别<strong>默认开启</strong>，打开页面即可直接说话</li>
              <li>点击麦克风按钮 <span class="tag">🎤</span> 可手动开关语音</li>
              <li>说完内容后说<strong>【发送】</strong>提交消息</li>
              <li>弹窗确认时，说<strong>【确认】</strong>发送，说<strong>【取消】</strong>放弃</li>
              <li>语音识别会自动过滤末尾的标点符号</li>
            </ul>
          </section>

          <section class="help-section">
            <h3>可用命令</h3>
            <div class="command-grid">
              <div class="command-card">
                <div class="cmd-title">查看待办</div>
                <div class="cmd-example">"我有哪些待办"<br>"帮我看看所有目标"</div>
              </div>
              <div class="command-card">
                <div class="cmd-title">查看某天安排</div>
                <div class="cmd-example">"明天有什么安排"<br>"6月5号有哪些事"</div>
              </div>
              <div class="command-card">
                <div class="cmd-title">创建待办</div>
                <div class="cmd-example">"帮我创建一个每周一到周五学英语，从6月1日到6月30日"</div>
              </div>
              <div class="command-card">
                <div class="cmd-title">修改待办</div>
                <div class="cmd-example">"把学英语改成每周二四"<br>（需先查看列表获取编号）</div>
              </div>
              <div class="command-card">
                <div class="cmd-title">删除待办</div>
                <div class="cmd-example">"删除学英语这个待办"<br>（需先查看列表获取编号）</div>
              </div>
              <div class="command-card">
                <div class="cmd-title">完成任务</div>
                <div class="cmd-example">"今天的英语打卡完成了"<br>"搞定今天的学英语"</div>
              </div>
              <div class="command-card">
                <div class="cmd-title">写日记</div>
                <div class="cmd-example">"帮我写个日记，今天心情不错"<br>"记录一下今天的收获"</div>
              </div>
            </div>
          </section>

          <section class="help-section">
            <h3>操作流程</h3>
            <ol class="flow-list">
              <li>直接对着麦克风说出你的需求</li>
              <li>AI 会分析并追问必要信息</li>
              <li>说<strong>【发送】</strong>提交消息</li>
              <li>AI 会朗读回复并自动更新日历</li>
              <li>如涉及创建/修改/删除，AI 会先展示方案，你说<strong>【确认】</strong>后执行</li>
            </ol>
          </section>

          <section class="help-section">
            <h3>键盘操作</h3>
            <ul>
              <li><span class="key">Enter</span> 发送消息</li>
              <li>点击 AI 消息下方的 <span class="tag">朗读</span> 可重新播放</li>
              <li>点击 <span class="tag">复制</span> 复制回复内容</li>
              <li>点击 <span class="tag">撤回</span> 撤销上一轮对话</li>
            </ul>
          </section>
        </div>
      </div>
    </transition>
  </teleport>
</template>

<script setup lang="ts">
defineProps<{ visible: boolean }>();
defineEmits<{ (e: 'close'): void }>();
</script>

<style scoped>
.help-overlay {
  position: fixed; top: 0; left: 0; right: 0; bottom: 0;
  background-color: rgba(92, 75, 55, 0.4);
  backdrop-filter: blur(3px);
  z-index: 3000;
}

.help-panel {
  position: fixed; bottom: 0; left: 0; right: 0;
  margin: 0 auto;
  width: 95%; max-width: 780px;
  height: 80vh;
  background: #fdfae9;
  border-top-left-radius: 24px;
  border-top-right-radius: 24px;
  border-top: 2px solid #d3c4a1;
  box-shadow: 0 -10px 40px rgba(92, 75, 55, 0.15);
  z-index: 3001;
  display: flex; flex-direction: column;
}

.help-header {
  display: flex; justify-content: space-between; align-items: center;
  padding: 24px 32px 16px;
  border-bottom: 2px dashed #d3c4a1;
  flex-shrink: 0;
}
.help-header h2 { color: #5c4b37; margin: 0; font-size: 1.4rem; }
.help-close {
  background: #fcf9ee; border: 1px solid #d3c4a1; color: #8c7a65;
  font-size: 18px; cursor: pointer; width: 36px; height: 36px;
  border-radius: 50%; display: flex; align-items: center; justify-content: center;
  transition: all 0.2s;
}
.help-close:hover { color: #fdfae9; background: #bc423f; border-color: #bc423f; }

.help-body {
  flex: 1; overflow-y: auto; padding: 24px 32px 40px;
}
.help-body::-webkit-scrollbar { width: 6px; }
.help-body::-webkit-scrollbar-thumb { background: #d3c4a1; border-radius: 4px; }

.help-section {
  margin-bottom: 28px;
  background: #fcf9ee;
  border: 1px solid #eaddc4;
  border-radius: 12px;
  padding: 20px 24px;
}
.help-section h3 {
  color: #5c4b37; font-size: 1.1rem; margin: 0 0 14px 0;
  display: flex; align-items: center; gap: 8px;
}
.help-section h3::before { content: '📖'; font-size: 16px; }
.help-section ul, .flow-list { margin: 0; padding-left: 20px; }
.help-section li { color: #5c4b37; font-size: 14px; line-height: 2; }
.help-section li strong { color: #bc423f; }

.tag {
  display: inline-block; padding: 1px 8px; background: #eaddc4;
  border-radius: 4px; font-size: 12px; color: #5c4b37; font-weight: bold;
}
.key {
  display: inline-block; padding: 2px 8px; background: #5c4b37; color: #fdfae9;
  border-radius: 4px; font-size: 12px; font-weight: bold; margin: 0 2px;
}

.command-grid {
  display: grid; grid-template-columns: 1fr 1fr; gap: 12px;
}
.command-card {
  background: #fdfae9; border: 1px solid #d3c4a1; border-radius: 8px;
  padding: 14px 16px;
}
.cmd-title { color: #5c4b37; font-weight: bold; font-size: 14px; margin-bottom: 8px; }
.cmd-example { color: #8c7a65; font-size: 13px; line-height: 1.6; }

.flow-list li { padding: 4px 0; }

/* 动画 */
.fade-enter-active, .fade-leave-active { transition: opacity 0.3s ease; }
.fade-enter-from, .fade-leave-to { opacity: 0; }
.slide-up-enter-active, .slide-up-leave-active {
  transition: transform 0.4s cubic-bezier(0.25, 1, 0.5, 1);
}
.slide-up-enter-from, .slide-up-leave-to { transform: translateY(100%); }
</style>
