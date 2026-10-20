<template>
  <!-- 全局登录端切换：单按钮，仅在登录页显示。
       按钮可自由拖动放入任意位置；点击弹出其余三个登录端的预览卡片，
       鼠标悬停显示对应端名称，点击预览卡片跳转到对应登录页 -->
  <div v-if="isLoginPage" class="float-root">
    <!-- Uiverse 切换按钮（点击展开/收起预览面板） -->
    <button
      class="float-btn"
      :class="{ open: panelOpen }"
      style="--clr: #7808d0"
      title="切换登录端"
      @mousedown.prevent="onDown($event)"
      @touchstart.prevent="onDown($event)"
      @click="toggle"
    >
      <span class="button__icon-wrapper">
        <svg
          viewBox="0 0 14 15"
          fill="none"
          xmlns="http://www.w3.org/2000/svg"
          class="button__icon-svg"
          width="10"
        >
          <path
            d="M13.376 11.552l-.264-10.44-10.44-.24.024 2.28 6.96-.048L.2 12.56l1.488 1.488 9.432-9.432-.048 6.912 2.304.024z"
            fill="currentColor"
          ></path>
        </svg>
        <svg
          viewBox="0 0 14 15"
          fill="none"
          width="10"
          xmlns="http://www.w3.org/2000/svg"
          class="button__icon-svg button__icon-svg--copy"
        >
          <path
            d="M13.376 11.552l-.264-10.44-10.44-.24.024 2.28 6.96-.048L.2 12.56l1.488 1.488 9.432-9.432-.048 6.912 2.304.024z"
            fill="currentColor"
          ></path>
        </svg>
      </span>
      切换
    </button>

    <!-- 登录端切换面板（当前端置首并标注，其余跟随） -->
    <Transition name="panel">
      <div v-if="panelOpen" class="preview-panel">
        <div
          v-for="zone in orderedZones"
          :key="zone.key"
          class="preview-card"
          :class="{ current: zone.key === currentZone?.key }"
          @click="goTo(zone.login)"
        >
          <div class="preview-row">
            <span class="preview-name">{{ zone.name }}</span>
            <span v-if="zone.key === currentZone?.key" class="preview-badge">当前</span>
          </div>
          <span class="preview-desc">{{ zone.desc }}</span>
        </div>
      </div>
    </Transition>
  </div>
</template>

<script setup>
// 其余三个登录端预览：市民端 → 街道办 → 部门端 → 管理端（当前端之外的三个）
import { ref, reactive, computed, onMounted, onBeforeUnmount } from 'vue'
import { useRoute, useRouter } from 'vue-router'

const route = useRoute()
const router = useRouter()

// 四个登录端定义及说明文案
const ZONES = [
  { key: 'citizen', name: '市民端', desc: '市民登录入口', login: '/login' },
  { key: 'staff', name: '部门端', desc: '部门人员登录入口', login: '/staff/login' },
  { key: 'street', name: '街道办', desc: '街道工作人员登录入口', login: '/street/login' },
  { key: 'admin', name: '管理端', desc: '仅限管理人员登录', login: '/admin/login' }
]
const LOGIN_PATHS = ZONES.map((z) => z.login)

const isLoginPage = computed(() => LOGIN_PATHS.includes(route.path))

// 当前登录端（按当前路由匹配）
const currentZone = computed(() => ZONES.find((z) => z.login === route.path))
// 有序列表：当前端放首位，其余按定义顺序跟随（供面板展示）
const orderedZones = computed(() => {
  const cur = currentZone.value
  if (!cur) return ZONES
  return [cur, ...ZONES.filter((z) => z.key !== cur.key)]
})

// 面板开关
const panelOpen = ref(false)

// 按钮位置（左上角，可拖动）
const SIZE = 0 // 按钮非固定方形，用内容撑开；距离计算时使用 clickable 尺寸
const pos = reactive({ x: 40, y: 80 })

// 单击展开/收起
function toggle() {
  // 拖动过则忽略单击
  if (moved || dragging) return
  panelOpen.value = !panelOpen.value
}

// 点击预览图：直接跳转到对应登录页（配合 App.vue 的滑动过渡）
function goTo(path) {
  const zone = ZONES.find((z) => z.login === path)
  if (!zone) return
  panelOpen.value = false
  router.push(path)
}

// 拖拽逻辑
let downStart = null
let moved = false
let dragging = false
let startPos = null

function onDown(e) {
  const p = pointOf(e)
  downStart = p
  startPos = { ...pos }
  moved = false
  dragging = false
  document.addEventListener('mousemove', onMove)
  document.addEventListener('mouseup', onUp)
  document.addEventListener('touchmove', onMove, { passive: false })
  document.addEventListener('touchend', onUp)
}

function pointOf(e) {
  if (e.touches && e.touches.length) {
    return { x: e.touches[0].clientX, y: e.touches[0].clientY }
  }
  return { x: e.clientX, y: e.clientY }
}

function onMove(e) {
  if (!downStart) return
  const p = pointOf(e)
  const dx = p.x - downStart.x
  const dy = p.y - downStart.y
  if (!moved && Math.abs(dx) + Math.abs(dy) < 6) return
  moved = true
  dragging = true
  pos.x = Math.max(4, Math.min(startPos.x + dx, window.innerWidth - 100))
  pos.y = Math.max(4, Math.min(startPos.y + dy, window.innerHeight - 60))
}

function onUp() {
  document.removeEventListener('mousemove', onMove)
  document.removeEventListener('mouseup', onUp)
  document.removeEventListener('touchmove', onMove)
  document.removeEventListener('touchend', onUp)
  downStart = null
  dragging = false
}

const onResize = () => {
  pos.x = Math.min(pos.x, Math.max(4, window.innerWidth - 120))
  pos.y = Math.min(pos.y, Math.max(4, window.innerHeight - 60))
}
onMounted(() => window.addEventListener('resize', onResize))
onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize)
})
</script>

<style scoped>
.float-root {
  position: fixed;
  z-index: 2500;
}

/* ===== Uiverse 切换按钮（用户提供，保留原样式） ===== */
.float-btn {
  line-height: 1;
  text-decoration: none;
  display: inline-flex;
  border: none;
  cursor: grab;
  align-items: center;
  gap: 0.75rem;
  background-color: var(--clr);
  color: #fff;
  border-radius: 10rem;
  font-weight: 600;
  padding: 0.75rem 1.5rem;
  padding-left: 20px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  transition: background-color 0.3s;
  box-shadow: 0 6px 20px rgba(120, 8, 208, 0.3);
  position: fixed;
  left: v-bind(pos.x + 'px');
  top: v-bind(pos.y + 'px');
}
.float-btn:active,
.float-btn.open {
  cursor: grabbing;
}
.float-btn.open {
  background-color: #000;
}

.button__icon-wrapper {
  flex-shrink: 0;
  width: 25px;
  height: 25px;
  position: relative;
  color: var(--clr);
  background-color: #fff;
  border-radius: 50%;
  display: grid;
  place-items: center;
  overflow: hidden;
}

.float-btn:hover {
  background-color: #000;
}

.float-btn:hover .button__icon-wrapper {
  color: #000;
}

.button__icon-svg--copy {
  position: absolute;
  transform: translate(-150%, 150%);
}

.float-btn:hover .button__icon-svg:first-child {
  transition: transform 0.3s ease-in-out;
  transform: translate(150%, -150%);
}

.float-btn:hover .button__icon-svg--copy {
  transition: transform 0.3s ease-in-out 0.1s;
  transform: translate(0);
}

/* ===== 登录端切换面板（竖排四排名称列表） ===== */
.preview-panel {
  position: fixed;
  top: v-bind(pos.y + 50 + 'px');
  left: v-bind(pos.x + 'px');
  display: flex;
  flex-direction: column;
  gap: 6px;
  background: rgba(255, 255, 255, 0.95);
  padding: 8px;
  border-radius: 12px;
  box-shadow: 0 12px 32px rgba(0, 0, 0, 0.15);
  backdrop-filter: blur(8px);
  min-width: 210px;
}
.preview-card {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 8px 12px;
  border-radius: 8px;
  cursor: pointer;
  transition: background-color 0.2s, padding-left 0.2s;
  user-select: none;
}
.preview-card:hover {
  background: rgba(120, 8, 208, 0.1);
  padding-left: 16px;
}
.preview-card.current {
  background: rgba(120, 8, 208, 0.12);
  cursor: default;
}
.preview-row {
  display: flex;
  align-items: center;
  gap: 8px;
}
.preview-name {
  font-size: 14px;
  font-weight: 600;
  color: #1f2937;
  white-space: nowrap;
}
.preview-badge {
  font-size: 11px;
  line-height: 1;
  padding: 3px 7px;
  border-radius: 20px;
  color: #fff;
  background: linear-gradient(145deg, #7808d0, #2575fc);
}
.preview-desc {
  font-size: 12px;
  color: #94a3b8;
  white-space: nowrap;
}
.preview-card:hover .preview-name {
  color: #7808d0;
}
.preview-card.current .preview-name {
  color: #7808d0;
}

/* 展开动画 */
.panel-enter-active,
.panel-leave-active {
  transition: opacity 0.25s ease, transform 0.25s ease;
}
.panel-enter-from,
.panel-leave-to {
  opacity: 0;
  transform: translateX(-12px);
}
</style>