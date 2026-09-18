<template>
  <svg
    class="app-icon"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    stroke-width="1.8"
    stroke-linecap="round"
    stroke-linejoin="round"
    aria-hidden="true"
  >
    <template v-if="name === 'search'">
      <circle cx="11" cy="11" r="6.5" />
      <path d="M16.2 16.2 21 21" />
    </template>
    <template v-else-if="name === 'compose'">
      <path d="M5 19h4l10.2-10.2a2 2 0 0 0-2.8-2.8L6.2 16.2V19z" />
      <path d="M13.7 6.7 17.3 10.3" />
    </template>
    <template v-else-if="name === 'calendar'">
      <rect x="3.5" y="5" width="17" height="15.5" rx="2" />
      <path d="M8 3.5v3M16 3.5v3M3.5 10h17" />
    </template>
    <template v-else-if="name === 'today'">
      <circle cx="12" cy="12" r="8" />
      <path d="M12 8v4.5l3 1.8" />
    </template>
    <template v-else-if="name === 'panel'">
      <rect x="3.5" y="4.5" width="17" height="15" rx="2" />
      <path d="M9.5 4.5v15" />
    </template>
    <template v-else-if="name === 'gear'">
      <path d="M4 21V14M4 10V3" />
      <path d="M12 21v-9M12 8V3" />
      <path d="M20 21v-5M20 12V3" />
      <circle cx="4" cy="12" r="2.1" />
      <circle cx="12" cy="8" r="2.1" />
      <circle cx="20" cy="16" r="2.1" />
    </template>
    <template v-else-if="name === 'sparkle'">
      <path d="M12 4.5 13.6 10.4 19.5 12 13.6 13.6 12 19.5 10.4 13.6 4.5 12 10.4 10.4 12 4.5z" />
    </template>
    <template v-else-if="name === 'switch'">
      <path d="M8 8H5.5A2.5 2.5 0 0 0 3 10.5v0A2.5 2.5 0 0 0 5.5 13H16" />
      <path d="M13 5l3 3-3 3" />
      <path d="M16 16h2.5A2.5 2.5 0 0 0 21 13.5v0A2.5 2.5 0 0 0 18.5 11H8" />
      <path d="M11 19l-3-3 3-3" />
    </template>
    <template v-else-if="name === 'logout'">
      <path d="M10 5.5H7A2.5 2.5 0 0 0 4.5 8v8A2.5 2.5 0 0 0 7 18.5h3" />
      <path d="M11 12h8.5" />
      <path d="M16.5 8.5 20.5 12l-4 3.5" />
    </template>
    <template v-else-if="name === 'chevron'">
      <path d="M9 6l6 6-6 6" />
    </template>
  </svg>
</template>

<script setup lang="ts">
defineProps<{
  name: 'search' | 'compose' | 'calendar' | 'today' | 'panel' | 'gear' | 'sparkle' | 'switch' | 'logout' | 'chevron';
}>();
</script>

<style scoped>
.app-icon {
  width: 18px;
  height: 18px;
  display: block;
  flex-shrink: 0;
}
</style>
