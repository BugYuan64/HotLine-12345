<template>
  <!-- 路由视图：仅登录页之间使用滑动切换动画，其余页面直接渲染，避免过渡动画卡住导致页面不跳转 -->
  <router-view v-slot="{ Component }">
    <transition v-if="isLoginRoute" :name="routerViewAnim">
      <component :is="Component" :key="$route.path" />
    </transition>
    <component v-else :is="Component" :key="$route.path" />
  </router-view>
  <FloatingBall />
</template>

<script setup>
// 应用根组件：挂载路由视图(含滑动切换动画)、全局悬浮方向切换按钮组
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import FloatingBall from './shared/FloatingBall.vue'

const route = useRoute()

// 登录页顺序（与悬浮按钮一致）
const LOGIN_ORDER = ['/login', '/street/login', '/staff/login', '/admin/login']
const loginIndexOf = (p) => {
  const i = LOGIN_ORDER.indexOf(p)
  return i
}

// 仅登录页之间切换时才套用滑动过渡动画，其余业务页直接渲染
const isLoginRoute = computed(() => loginIndexOf(route.path) !== -1)

// 当前滑动动画方向：slide-left(内容向左滑入，新页位于右侧) / slide-right(内容向右滑入，新页位于左侧)
const routerViewAnim = ref('slide-left')
let prevIndex = loginIndexOf(route.path)
watch(
  () => route.path,
  (np) => {
    const ni = loginIndexOf(np)
    const pi = prevIndex
    prevIndex = ni
    // 仅在登录页之间切换时计算环形最短滑动方向
    if (ni === -1 || pi === -1) {
      routerViewAnim.value = 'slide-left'
      return
    }
    const n = LOGIN_ORDER.length
    const forward = (ni - pi + n) % n   // 下一端所需步数
    const backward = (pi - ni + n) % n  // 上一端所需步数
    routerViewAnim.value = forward <= backward ? 'slide-left' : 'slide-right'
  }
)
</script>

<style>
/* 登录页滑动切换特效 */
.slide-left-enter-active,
.slide-left-leave-active,
.slide-right-enter-active,
.slide-right-leave-active {
  transition: all 0.32s cubic-bezier(0.32, 0.72, 0.32, 1);
}
/* 向左滑动（新页从右侧进入，旧页向左侧退出） */
.slide-left-enter-from {
  transform: translateX(100%);
  opacity: 0;
}
.slide-left-leave-to {
  transform: translateX(-100%);
  opacity: 0;
}
/* 向右滑动（新页从左侧进入，旧页向右侧退出） */
.slide-right-enter-from {
  transform: translateX(-100%);
  opacity: 0;
}
.slide-right-leave-to {
  transform: translateX(100%);
  opacity: 0;
}
</style>