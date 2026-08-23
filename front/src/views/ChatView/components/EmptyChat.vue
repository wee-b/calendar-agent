<template>
  <div class="empty-chat">
    <h2>有什么我能帮你的吗？</h2>
    <div class="prompt-grid">
      <button
        v-for="prompt in prompts"
        :key="prompt"
        @click="$emit('select-prompt', prompt)"
      >
        {{ prompt }}
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
const prompts = [
  '帮我规划明天的日程',
  '本周有哪些待办需要优先处理？',
  '安排一个复习计划',
  '帮我创建一个会议提醒',
  '总结今天的日程完成情况',
  '取消后天的日程'
];

defineEmits<{
  (e: 'select-prompt', prompt: string): void;
}>();
</script>
