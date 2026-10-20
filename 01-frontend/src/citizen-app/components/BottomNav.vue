<!--
  市民端公共底部 Tab 导航
  - 当前路由由 props.activeKey / 默认根据 route.path 自动匹配
  - 严禁使用 router.back()，所有跳转均为 router.push 显式路径
  - 政务适老化：最小触控 48px，文字加粗，激活态颜色高对比
-->
<template>
  <nav class="bottom-nav" role="navigation" aria-label="市民端主导航">
    <div
      v-for="item in tabs"
      :key="item.key"
      class="nav-btn"
      :class="{ active: currentKey === item.key }"
      role="button"
      tabindex="0"
      :aria-current="currentKey === item.key ? 'page' : undefined"
      @click="go(item)"
      @keydown.enter="go(item)"
      @keydown.space.prevent="go(item)"
    >
      <el-icon class="icon" :aria-hidden="true"><component :is="item.icon" /></el-icon>
      <span class="label">{{ item.label }}</span>
    </div>
  </nav>
</template>

<script setup>
import { computed } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { HomeFilled, EditPen, Search, User } from '@element-plus/icons-vue'

const router = useRouter()
const route = useRoute()

const props = defineProps({
  /** 显式指定当前激活 tab；为空时按路由自动匹配 */
  activeKey: { type: String, default: '' }
})

const tabs = [
  { key: 'home',          label: '首页',     icon: HomeFilled, path: '/home' },
  { key: 'submit-appeal', label: '我的诉求', icon: EditPen, path: '/submit-appeal' },
  { key: 'progress',      label: '办理进度', icon: Search, path: '/progress' },
  { key: 'my',            label: '我的',     icon: User, path: '/my' }
]

const currentKey = computed(() => {
  if (props.activeKey) return props.activeKey
  const p = route.path
  if (p === '/' || p.startsWith('/home')) return 'home'
  if (p.startsWith('/submit-appeal') || p.startsWith('/complaint')) return 'submit-appeal'
  if (p.startsWith('/progress')) return 'progress'
  if (p.startsWith('/my')) return 'my'
  return ''
})

/** 显式 push，绝不使用 router.back()，避免误退回登录页 */
const go = (item) => {
  if (route.path === item.path) return
  router.push(item.path)
}
</script>

<style scoped>
.bottom-nav {
  position: fixed;
  bottom: 0;
  left: 0;
  right: 0;
  background: #fff;
  border-top: 1px solid var(--el-border-color-light);
  display: flex;
  justify-content: space-around;
  padding: 8px 0 calc(8px + env(safe-area-inset-bottom));
  z-index: 50;
  box-shadow: 0 -2px 8px rgba(15, 23, 42, 0.04);
}
.nav-btn {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2px;
  min-width: 64px;
  min-height: 48px;
  padding: 4px 12px;
  font-size: 13px;
  font-weight: 500;
  color: var(--el-text-color-secondary);
  cursor: pointer;
  border-radius: var(--app-radius-md);
  transition: color 0.2s ease, background-color 0.2s ease, transform 0.15s ease;
  user-select: none;
  -webkit-tap-highlight-color: transparent;
}
.nav-btn:hover,
.nav-btn:focus-visible {
  color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
  outline: none;
}
.nav-btn.active {
  color: var(--el-color-primary);
  font-weight: 700;
}
.nav-btn .icon {
  font-size: 22px;
  line-height: 1;
}
.nav-btn .label {
  font-size: 12px;
  letter-spacing: 0.5px;
}
</style>