<template>
  <div class="operations-shell">
    <aside class="operations-sidebar">
      <div class="sidebar-brand">
        <span class="brand-mark"><AppIcon name="monitor" /></span>
        <div class="brand-copy"><strong>运维中心</strong><span>监控与分析</span></div>
      </div>

      <nav class="operations-tabs" aria-label="运维导航">
        <RouterLink :to="{ path: '/operations', query: { tab: 'tools' } }"
          :class="{ active: activeTab === 'tools' }" :aria-current="activeTab === 'tools' ? 'page' : undefined">
          <AppIcon name="monitor" /><span>工具调用</span>
        </RouterLink>
        <RouterLink :to="{ path: '/operations', query: { tab: 'api' } }"
          :class="{ active: activeTab === 'api' }" :aria-current="activeTab === 'api' ? 'page' : undefined">
          <AppIcon name="api" /><span>API 调用</span>
        </RouterLink>
      </nav>

      <RouterLink to="/conversation" class="back-link"><AppIcon name="chevron" /><span>返回应用</span></RouterLink>
    </aside>

    <main class="operations-main" :aria-label="activeTab === 'tools' ? '工具调用' : 'API 调用'">
      <div class="operations-content">
        <KeepAlive><ToolCallsPanel v-if="activeTab === 'tools'" /></KeepAlive>
        <section v-if="activeTab === 'api'" class="api-panel">
          <header class="api-heading">
            <span class="eyebrow">运行概览 <span class="account-tag">当前账号</span></span>
            <h1>API 调用</h1>
            <p>API 调用统计与运行监控。</p>
          </header>
          <div class="reserved-state">
            <span class="reserved-icon"><AppIcon name="api" /></span>
            <h2>API 调用统计暂未开放</h2>
            <p>此处保留 API 调用统计入口，后续将在这里展示。</p>
          </div>
        </section>
      </div>
    </main>
  </div>
</template>

<script setup lang="ts">
import { computed, KeepAlive } from 'vue';
import { RouterLink, useRoute } from 'vue-router';
import AppIcon from '../LayoutView/components/AppIcon.vue';
import ToolCallsPanel from './components/ToolCallsPanel.vue';

const route = useRoute();
const activeTab = computed(() => route.query.tab === 'api' ? 'api' : 'tools');
</script>

<style scoped>
.operations-shell { display: flex; width: 100%; height: 100%; overflow: hidden; color: #27272a; background: #fafafa; }
.operations-sidebar { width: 208px; flex-shrink: 0; min-height: 0; display: flex; flex-direction: column; padding: 28px 14px 20px; border-right: 1px solid #e4e4e7; background: #f7f7f8; }
.sidebar-brand { display: flex; align-items: center; gap: 11px; padding: 0 10px; margin-bottom: 36px; }
.brand-mark { width: 32px; height: 32px; display: grid; place-items: center; flex-shrink: 0; border-radius: 8px; background: #18181b; color: #fff; }
.brand-copy { display: flex; flex-direction: column; gap: 5px; }
.brand-copy strong { font-size: 14px; font-weight: 650; }
.brand-copy > span { font-size: 10px; color: #a1a1aa; }
.operations-tabs { display: flex; flex-direction: column; gap: 7px; }
.operations-tabs a { display: flex; align-items: center; gap: 10px; min-height: 42px; padding: 11px 14px; border-radius: 8px; color: #71717a; font-size: 13px; text-decoration: none; transition: background .15s, color .15s; }
.operations-tabs a:hover { background: #ececef; color: #27272a; }
.operations-tabs a.active { background: #18181b; color: #fff; }
.operations-tabs .app-icon { width: 17px; height: 17px; }
a:focus-visible { outline: 2px solid #64748b; outline-offset: 3px; }
.back-link { display: inline-flex; align-items: center; gap: 8px; margin-top: auto; padding: 12px 14px 0; border-top: 1px solid #e4e4e7; color: #71717a; text-decoration: none; font-size: 12px; }
.back-link:hover { color: #18181b; }
.back-link .app-icon { width: 13px; height: 13px; transform: rotate(180deg); }
.operations-main { flex: 1; min-width: 0; min-height: 0; overflow: auto; padding: 36px 30px 44px; }
.operations-content { max-width: 1240px; margin: 0 auto; }
.api-heading { margin-bottom: 28px; }
.eyebrow { display: flex; align-items: center; gap: 10px; color: #71717a; font-size: 12px; font-weight: 600; }
.account-tag { padding: 3px 7px; border: 1px solid #e4e4e7; border-radius: 5px; background: #fff; color: #71717a; font-size: 10px; font-weight: 400; }
h1 { margin: 9px 0; color: #18181b; font-size: 28px; letter-spacing: -.7px; }
.api-heading p { margin: 0; color: #71717a; font-size: 13px; line-height: 1.7; }
.reserved-state { min-height: 350px; display: flex; flex-direction: column; align-items: center; justify-content: center; padding: 40px 24px; border: 1px dashed #d4d4d8; border-radius: 12px; background: #fff; text-align: center; }
.reserved-icon { width: 52px; height: 52px; display: grid; place-items: center; border: 1px solid #ececef; border-radius: 14px; background: #fafafa; color: #a1a1aa; }
.reserved-icon .app-icon { width: 26px; height: 26px; }
.reserved-state h2 { margin: 22px 0 9px; font-size: 16px; font-weight: 550; color: #52525b; }
.reserved-state p { margin: 0; max-width: 360px; color: #a1a1aa; font-size: 12px; line-height: 1.8; }
@media (max-width: 760px) {
  .operations-sidebar { width: 84px; padding: 22px 10px 18px; }
  .sidebar-brand { flex-direction: column; gap: 9px; padding: 0; margin-bottom: 26px; }
  .brand-copy strong { font-size: 11px; }
  .brand-copy > span { display: none; }
  .operations-tabs a { flex-direction: column; gap: 7px; padding: 12px 3px; font-size: 11px; white-space: nowrap; }
  .back-link { flex-direction: column; gap: 7px; padding: 14px 0 0; font-size: 10px; }
  .operations-main { padding: 24px 16px 32px; }
  h1 { font-size: 24px; }
  .reserved-state { min-height: 300px; padding: 32px 18px; }
}
</style>
