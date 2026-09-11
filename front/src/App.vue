<template>
  <router-view></router-view>
</template>

<style>
body,
html {
  margin: 0;
  padding: 0;
  width: 100%;
  height: 100%;
  font-family: Inter, ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
  overflow: hidden;
  color: #101828;
  background: #f5f7fb;
}

* {
  box-sizing: border-box;
}

button,
input,
textarea {
  font: inherit;
}

#app {
  max-width: 100% !important;
  margin: 0 !important;
  padding: 0 !important;
  text-align: left;
  width: 100%;
  height: 100%;
}
</style>
