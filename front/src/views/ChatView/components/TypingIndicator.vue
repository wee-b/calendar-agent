<template>
  <div class="typing-indicator" role="status" aria-label="正在生成回复">
    <span></span><span></span><span></span>
  </div>
</template>

<style scoped>
.typing-indicator {
  display: flex;
  align-items: center;
  gap: 5px;
  height: 24px;
}

.typing-indicator span {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #8a8a91;
  animation: blink 1.2s infinite ease-in-out;
}

.typing-indicator span:nth-child(2) { animation-delay: 0.16s; }
.typing-indicator span:nth-child(3) { animation-delay: 0.32s; }

@keyframes blink {
  0%, 60%, 100% { opacity: 0.25; transform: translateY(0); }
  30% { opacity: 1; transform: translateY(-3px); }
}

@media (prefers-reduced-motion: reduce) {
  .typing-indicator span { animation: none; opacity: 0.75; }
}
</style>
