<script setup lang="ts">
export type IconName =
  | 'brand'
  | 'chat'
  | 'library'
  | 'chart'
  | 'user'
  | 'system'
  | 'users'
  | 'building'
  | 'flask'
  | 'trace'
  | 'logout'
  | 'menu'
  | 'close'
  | 'arrow'
  | 'plus'
  | 'lock'
  | 'file'
  | 'spark'

defineProps<{ name: IconName }>()

const paths: Record<IconName, string[]> = {
  brand: ['M4 4h6v6H4z M14 4h6v6h-6z M4 14h6v6H4z M14 14h6v6h-6z'],
  chat: ['M20 11.5a8 8 0 0 1-8 8H4l1.5-4A8 8 0 1 1 20 11.5Z', 'M8 10h8 M8 14h5'],
  library: ['M4 5h4v15H4z M10 5h4v15h-4z M16 6l3-1 4 14-3 1z'],
  chart: ['M4 4v16h16', 'M8 16v-5 M13 16V7 M18 16v-8'],
  user: ['M16 7a4 4 0 1 1-8 0 4 4 0 0 1 8 0Z', 'M4 21v-2a8 8 0 0 1 16 0v2'],
  system: ['M3 4h18v12H3z', 'M8 20h8 M12 16v4 M6 10h3l2-3 3 6 2-3h2'],
  users: [
    'M15 7a3 3 0 1 1-6 0 3 3 0 0 1 6 0Z M6 20v-2a6 6 0 0 1 12 0v2',
    'M18 4a3 3 0 0 1 0 6 M21 19v-2a5 5 0 0 0-3-4 M6 4a3 3 0 0 0 0 6 M3 19v-2a5 5 0 0 1 3-4',
  ],
  building: ['M5 21V3h14v18 M3 21h18 M9 7h1 M14 7h1 M9 11h1 M14 11h1 M10 21v-6h4v6'],
  flask: ['M9 3h6 M10 3v7L4 19a1 1 0 0 0 1 2h14a1 1 0 0 0 1-2l-6-9V3 M7 15h10'],
  trace: ['M4 5h6v6H4z M14 14h6v6h-6z M10 8h7v6 M7 11v6h7'],
  logout: ['M10 4H4v16h6 M13 8l4 4-4 4 M8 12h12'],
  menu: ['M4 6h16 M4 12h16 M4 18h16'],
  close: ['M6 6l12 12 M18 6 6 18'],
  arrow: ['M5 12h14 M13 6l6 6-6 6'],
  plus: ['M12 5v14 M5 12h14'],
  lock: ['M7 10V7a5 5 0 0 1 10 0v3 M5 10h14v11H5z M12 14v3'],
  file: ['M6 3h8l4 4v14H6z M14 3v5h4 M9 12h6 M9 16h4'],
  spark: ['m12 3 2.5 6.5L21 12l-6.5 2.5L12 21l-2.5-6.5L3 12l6.5-2.5Z'],
}
</script>

<template>
  <svg
    class="app-icon"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    stroke-width="1.6"
    stroke-linecap="round"
    stroke-linejoin="round"
    aria-hidden="true"
  >
    <path
      v-for="(path, index) in paths[name]"
      :key="index"
      :d="path"
    />
  </svg>
</template>
