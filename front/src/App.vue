<template>
  <router-view></router-view>
</template>

/* src/App.vue */
<style>
/* 全局基础样式，消除浏览器默认边距 */
body, html {
  margin: 0;
  padding: 0;
  width: 100%;
  height: 100%;
  font-family: system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
  overflow: hidden; /* 防止最外层出现意外的滚动条 */
}

* {
  box-sizing: border-box;
}

/* 覆盖 Vite 可能遗留的挂载点默认样式 */
#app {
  max-width: 100% !important;
  margin: 0 !important;
  padding: 0 !important;
  text-align: left;
  width: 100%;
  height: 100%;
}
</style>