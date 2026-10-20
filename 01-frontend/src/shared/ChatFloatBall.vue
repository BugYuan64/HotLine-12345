<!--
  通用智能助手拖拽气泡（四端共用：citizen/staff/street/admin）
  - 悬浮于各端任意页面右下角（可拖动至任意位置，位置本地持久化）
  - 点击展开聊天弹窗：调用对应端 SSE 接口，RAG 检索各自独立知识库
  - 聊天记录与对应端 Chat 页面共用同一 localStorage（chat_history_{scope}），对话连续
-->
<template>
  <div class="chat-float">
    <!-- 悬浮球（可拖动） -->
    <button
      class="ball-btn"
      :class="{ open: visible }"
      :style="{ left: pos.x + 'px', top: pos.y + 'px' }"
      title="智能助手"
      @pointerdown="startDrag"
      @click="toggle"
    >
      <el-icon :size="22"><ChatDotRound /></el-icon>
      <span v-if="!visible && unread" class="ball-badge">{{ unread }}</span>
    </button>

    <!-- 聊天弹窗（黑色顶栏可拖动；右上角放大/还原、关闭） -->
    <transition name="ball-pop">
      <div
        v-if="visible"
        class="ball-panel"
        :class="{ maximized }"
        :style="{ left: panelLeft + 'px', top: panelTop + 'px' }"
      >
        <div class="panel-head" @pointerdown="startPanelDrag">
          <span class="head-title">{{ cfg.welcome }}</span>
          <span class="head-tag">{{ cfg.kbName }}</span>
          <span class="head-act">
            <el-icon
              class="head-btn"
              :size="16"
              :title="maximized ? '还原窗口' : '放大窗口'"
              @click="toggleMax"
              @pointerdown.stop
            >
              <ScaleToOriginal v-if="maximized" />
              <FullScreen v-else />
            </el-icon>
            <el-icon class="head-btn" :size="16" title="关闭" @click="visible = false" @pointerdown.stop>
              <Close />
            </el-icon>
          </span>
        </div>

        <div ref="ballListRef" class="panel-body">
          <div v-if="messages.length === 0" class="ball-welcome">
            <div class="ball-welcome-icon"><el-icon :size="18"><ChatDotRound /></el-icon></div>
            <div class="ball-welcome-text">{{ cfg.sub }}</div>
            <div class="ball-quick" v-for="q in cfg.quick" :key="q" @click="send(q)">{{ q }}</div>
            <el-button link type="primary" class="ball-quick-more" @click="send('查看全部推荐问题')">试试查看全部</el-button>
          </div>
          <div
            v-for="(msg, i) in messages"
            :key="i"
            class="ball-msg"
            :class="msg.role === 'user' ? 'mine' : 'ai'"
          >
            <div v-if="msg.tools && msg.tools.length" style="margin-bottom:4px;display:flex;flex-wrap:wrap;gap:4px;">
              <span v-for="(t, ti) in msg.tools" :key="ti" style="font-size:10px;color:#8a8f98;background:#f0f1f3;border-radius:8px;padding:1px 6px;">{{ t.phase === 'end' ? '✅' : '🔍' }} {{ t.name }}</span>
            </div>
          <div class="ball-bubble">{{ msg.content }}<span v-if="msg.streaming" class="cursor">▍</span></div>
          </div>
        </div>

        <!-- 动态衔接建议（放在输入框上方，仅显示最新一轮 AI 建议） -->
        <div v-if="currentSuggestions.length" class="ball-sug-bar">
          <span v-for="(s, i) in currentSuggestions" :key="i" class="ball-sug-chip" @click="send(s)">{{ s }} →</span>
        </div>

        <div class="panel-input">
          <el-input
            v-model="input"
            type="textarea"
            :rows="2"
            resize="none"
            maxlength="500"
            placeholder="Enter 发送"
            :disabled="sending"
            @keydown.enter.exact.prevent="send()"
          />
          <el-button type="primary" :loading="sending" :disabled="!input.trim()" @click="send()">发送</el-button>
        </div>
      </div>
    </transition>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { ArrowRight, ChatDotRound, Close, FullScreen, ScaleToOriginal } from '@element-plus/icons-vue'
import { streamChat, loadChatHistory, saveChatHistory } from '../citizen-app/api/chat'

const props = defineProps({
  // 知识库作用域：citizen / staff / street / admin（决定接口路径、token、知识库、文案）
  scope: { type: String, default: 'citizen' }
})

/** 各端配置映射（接口路径 + token 存储键 + 展示文案），与 chat.js 路径推断完全一致 */
const SCOPE_CFG = {
  citizen: {
    path: '/api/v1/public/chat/stream', tokenKey: 'citizen_token',
    kbName: '市民知识库', welcome: '12345智能助手',
    sub: '您好，我是小政，可查诉求进度、补全描述，可咨询受理范围、办理流程等问题',
    quick: ['帮我查一下我的诉求办理进度', '怎么写诉求处理得更快？', '对办理结果不满意怎么办？']
  },
  staff: {
    path: '/api/v1/staff/chat/stream', tokenKey: 'staff_token',
    kbName: '部门知识库', welcome: '部门智能助理',
    sub: '可查本部门工单/详情/时间线/工作台汇总，可解答签收、时限、退回、延期等规范',
    quick: ['看看我们部门的工作台汇总', '帮我查一下最近的工单', '工单办理时限是几天？']
  },
  street: {
    path: '/api/v1/street/chat/stream', tokenKey: 'street_token',
    kbName: '街道办知识库', welcome: '街道办智能助理',
    sub: '可查本街道工单与分发预警，可解答属地责任、签收、时限、网格联动等规范',
    quick: ['查一下分到我们街道的工单', '有哪些预警分发到我们街道办？', '街道办属地范围是什么？']
  },
  admin: {
    path: '/api/v1/admin/chat/stream', tokenKey: 'admin_token',
    kbName: '管理员知识库', welcome: '平台管理助理',
    sub: '可查工单/诉求/审计/预警/预判/账号/待办，可维护知识库并触发扫描与预判',
    quick: ['帮我查一下最近的工单（含终审/归档）', '看看全局待办汇总', '账号审核流程是什么？']
  }
}

const cfg = computed(() => SCOPE_CFG[props.scope] || SCOPE_CFG.citizen)
const scope = computed(() => props.scope)

const visible = ref(false)
const unread = ref(0)
const input = ref('')
const sending = ref(false)
const currentSuggestions = ref([])
const messages = reactive([])
const ballListRef = ref(null)
let abortFn = null

/* ---------------- 可拖动（坐标本地持久化，按端隔离） ---------------- */
const BALL_W = 54
const PANEL_W = 380
const PANEL_H = 520
const STORE_KEY = () => `chat_ball_pos_${scope.value}`

const pos = ref(loadPos())
function loadPos() {
  try {
    const raw = localStorage.getItem(STORE_KEY())
    if (raw) {
      const p = JSON.parse(raw)
      if (p && typeof p.x === 'number' && typeof p.y === 'number') return p
    }
  } catch { /* 忽略 */ }
  // 默认右下角（避开侧边栏 220px）
  return { x: Math.max(240, window.innerWidth - BALL_W - 26), y: window.innerHeight - BALL_W - 26 }
}
/** 弹窗放大态：放大后居中大窗（与客服页面同款大界面），还原后回到小窗 */
const maximized = ref(false)
/** 弹窗被拖动后的自定义位置（null = 默认跟随悬浮球上方） */
const panelPos = ref(null)
/** 窗口尺寸变化触发器（视口 resize 时重新计算弹窗边界） */
const winTick = ref(0)

/** 弹窗当前尺寸（普通 380×520；放大后最大 1080 宽、86% 视口高） */
const panelW = computed(() => {
  winTick.value
  return maximized.value ? Math.min(1080, Math.round(window.innerWidth * 0.94)) : PANEL_W
})
const panelH = computed(() => {
  winTick.value
  return maximized.value ? Math.round(window.innerHeight * 0.86) : PANEL_H
})

/** 弹窗位置：放大→视口居中；拖动过→自定义位置；否则默认放在悬浮球上方 */
const panelLeft = computed(() => {
  winTick.value
  if (maximized.value) return Math.max(8, Math.round((window.innerWidth - panelW.value) / 2))
  if (panelPos.value) return Math.max(4, Math.min(panelPos.value.x, window.innerWidth - panelW.value - 4))
  return Math.max(8, Math.min(pos.value.x, window.innerWidth - panelW.value - 8))
})
const panelTop = computed(() => {
  winTick.value
  if (maximized.value) return Math.max(8, Math.round((window.innerHeight - panelH.value) / 2))
  if (panelPos.value) return Math.max(4, Math.min(panelPos.value.y, window.innerHeight - panelH.value - 4))
  return Math.max(8, Math.min(pos.value.y - panelH.value - 14, window.innerHeight - panelH.value - 8))
})

/** 放大 / 还原切换（对话记录连续，切换后滚到底部） */
function toggleMax() {
  maximized.value = !maximized.value
  scrollToBottom()
}

/* ---------------- 弹窗拖动（按住黑色顶栏拖动，放大态下禁用） ---------------- */
let panelDown = null
let panelStart = null
let panelMoved = false

function startPanelDrag(e) {
  if (maximized.value) return // 放大居中时不拖动
  if (e.target.closest('.head-act')) return // 点在按钮区不触发拖动
  panelDown = pointOf(e)
  panelStart = { x: panelLeft.value, y: panelTop.value }
  panelMoved = false
  document.addEventListener('pointermove', onPanelDrag)
  document.addEventListener('pointerup', endPanelDrag)
}
function onPanelDrag(e) {
  if (!panelDown) return
  const dx = e.clientX - panelDown.x
  const dy = e.clientY - panelDown.y
  if (!panelMoved && Math.abs(dx) + Math.abs(dy) < 4) return
  panelMoved = true
  panelPos.value = {
    x: Math.max(4, Math.min(panelStart.x + dx, window.innerWidth - panelW.value - 4)),
    y: Math.max(4, Math.min(panelStart.y + dy, window.innerHeight - panelH.value - 4))
  }
}
function endPanelDrag() {
  document.removeEventListener('pointermove', onPanelDrag)
  document.removeEventListener('pointerup', endPanelDrag)
  panelDown = null
}

function onWinResize() { winTick.value++ }

let downStart = null
let startPos = null
let moved = false
let dragging = false

function pointOf(e) {
  return { x: e.clientX, y: e.clientY }
}
function startDrag(e) {
  downStart = pointOf(e)
  startPos = { ...pos.value }
  moved = false
  dragging = false
  document.addEventListener('pointermove', onDrag)
  document.addEventListener('pointerup', endDrag)
}
function onDrag(e) {
  if (!downStart) return
  const dx = e.clientX - downStart.x
  const dy = e.clientY - downStart.y
  if (!moved && Math.abs(dx) + Math.abs(dy) < 6) return
  moved = true
  dragging = true
  pos.value.x = Math.max(4, Math.min(startPos.x + dx, window.innerWidth - BALL_W - 4))
  pos.value.y = Math.max(4, Math.min(startPos.y + dy, window.innerHeight - BALL_W - 4))
}
function endDrag() {
  document.removeEventListener('pointermove', onDrag)
  document.removeEventListener('pointerup', endDrag)
  downStart = null
  dragging = false
  if (moved) {
    try { localStorage.setItem(STORE_KEY(), JSON.stringify(pos.value)) } catch { /* 忽略 */ }
  }
}

/* ---------------- 聊天逻辑 ---------------- */
const scrollToBottom = () => {
  nextTick(() => {
    if (ballListRef.value) ballListRef.value.scrollTop = ballListRef.value.scrollHeight
  })
}

/** 单击展开/收起（拖动过则忽略点击） */
function toggle() {
  if (moved || dragging) return
  visible.value = !visible.value
}

watch(visible, (val) => {
  if (val) unread.value = 0
})

function send(preset) {
  const question = (preset || input.value).trim()
  if (!question || sending.value) return
  input.value = ''
  sending.value = true
  currentSuggestions.value = []

  messages.push({ role: 'user', content: question })
  const aiMsg = reactive({ role: 'assistant', content: '', streaming: true, tools: [], suggestions: [] })
  messages.push(aiMsg)
  scrollToBottom()

  const history = messages
    .filter(m => !m.streaming && (m.role === 'user' || m.role === 'assistant'))
    .slice(-6)
    .map(m => ({ role: m.role, content: m.content }))

  abortFn = streamChat(
    { question, history, scope: scope.value },
    (evt) => {
      if (evt.type === 'tool') {
      if (!aiMsg.tools) aiMsg.tools = []
      aiMsg.tools.push({ name: evt.name || '', phase: evt.phase })
      scrollToBottom()
    } else if (evt.type === 'delta') {
        aiMsg.content += evt.content
        scrollToBottom()
      } else if (evt.type === 'suggestions') {
        // 动态衔接建议：刷新到全局 currentSuggestions，显示在输入框上方
        currentSuggestions.value = evt.suggestions || []
      } else if (evt.type === 'error') {
        aiMsg.content += aiMsg.content ? '\n\n（回答中断）' : `抱歉，${evt.message}`
      }
    },
    () => {
      aiMsg.streaming = false
      sending.value = false
      abortFn = null
      if (!aiMsg.content) aiMsg.content = '抱歉，智能助手暂时无法回答，请稍后再试。'
      saveChatHistory(scope.value, messages)
      if (!visible.value && aiMsg.content) unread.value++
      scrollToBottom()
    },
    { getToken: () => localStorage.getItem(cfg.value.tokenKey) || '', chatPath: cfg.value.path }
  )
}

onMounted(() => {
  const saved = loadChatHistory(scope.value)
  for (const m of saved) {
    messages.push(reactive({ role: m.role, content: m.content, streaming: false }))
  }
  window.addEventListener('resize', onWinResize)
})

onUnmounted(() => {
  if (abortFn) abortFn()
  window.removeEventListener('resize', onWinResize)
  document.removeEventListener('pointermove', onPanelDrag)
  document.removeEventListener('pointerup', endPanelDrag)
})
</script>

<style scoped>
.chat-float { position: fixed; z-index: 3000; }

/* 悬浮球 */
.ball-btn {
  position: fixed;
  width: 54px;
  height: 54px;
  border-radius: 50%;
  border: none;
  background: #18181b;
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: grab;
  box-shadow: 0 6px 20px rgba(0, 0, 0, 0.18);
  transition: transform 0.15s;
  user-select: none;
}
.ball-btn:hover { transform: scale(1.06); }
.ball-btn:active { cursor: grabbing; }
.ball-btn.open { background: #52525b; }
.ball-badge {
  position: absolute;
  top: -4px;
  right: -4px;
  min-width: 18px;
  height: 18px;
  line-height: 18px;
  border-radius: 9px;
  background: #e5484d;
  color: #fff;
  font-size: 11px;
  text-align: center;
  padding: 0 4px;
}

/* 聊天弹窗 */
.ball-panel {
  position: fixed;
  z-index: 3001;
  width: 380px;
  height: 520px;
  background: #fff;
  border: 1px solid #ececec;
  border-radius: 14px;
  box-shadow: 0 12px 40px rgba(0, 0, 0, 0.16);
  display: flex;
  flex-direction: column;
  overflow: hidden;
  transition: width 0.22s ease, height 0.22s ease, border-radius 0.22s ease;
}
/* 放大态：居中大窗（与智能助手页面同款大界面） */
.ball-panel.maximized {
  width: min(1080px, 94vw);
  height: 86vh;
  border-radius: 10px;
}

.ball-pop-enter-active, .ball-pop-leave-active { transition: all 0.2s ease; }
.ball-pop-enter-from, .ball-pop-leave-to { opacity: 0; transform: translateY(12px) scale(0.96); }

/* 黑色顶栏：普通态可按住拖动，放大态居中不可拖 */
.panel-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 14px 16px;
  background: #18181b;
  color: #fff;
  cursor: move;
  user-select: none;
  touch-action: none;
}
.ball-panel.maximized .panel-head { cursor: default; }
.head-title { font-size: 14px; font-weight: 600; }
.head-tag {
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.16);
  color: #d4d4d8;
}
/* 右上角操作按钮区（放大/还原、关闭） */
.head-act {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 12px;
}
.head-btn { cursor: pointer; opacity: 0.8; transition: opacity 0.15s; }
.head-btn:hover { opacity: 1; }

/* 放大态内部空间加宽，消息与推荐胶囊更舒展 */
.ball-panel.maximized .panel-body { padding: 22px 36px; }
.ball-panel.maximized .ball-msg { margin-bottom: 16px; }
.ball-panel.maximized .ball-bubble { max-width: 72%; font-size: 14px; }
.ball-panel.maximized .ball-panel.maximized .ball-welcome { padding-top: 6vh; }
.ball-panel.maximized .ball-welcome-text { font-size: 13.5px; }

.panel-body {
  flex: 1;
  overflow-y: auto;
  padding: 14px;
  background: #fafafa;
}
.panel-body::-webkit-scrollbar { width: 4px; }
.panel-body::-webkit-scrollbar-thumb { background: #e5e5e5; border-radius: 2px; }

.ball-welcome { text-align: center; padding-top: 22px; }
.ball-welcome-icon {
  width: 44px;
  height: 44px;
  margin: 0 auto 10px;
  border-radius: 12px;
  background: #18181b;
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
}
.ball-welcome-text { font-size: 12.5px; color: #8e8e93; margin-bottom: 14px; line-height: 1.6; }
.ball-quick {
  display: inline-block;
  margin: 5px;
  padding: 6px 12px;
  border: 1px solid #e5e5e5;
  border-radius: 16px;
  font-size: 12px;
  color: #52525b;
  background: #fff;
  cursor: pointer;
}
.ball-quick:hover { border-color: #18181b; color: #18181b; }
.ball-quick-more { align-self: flex-start; font-size: 11px; }

.ball-msg { display: flex; margin-bottom: 12px; }
.ball-msg.mine { justify-content: flex-end; }
.ball-msg.ai { flex-direction: column; align-items: flex-start; }
.ball-bubble {
  max-width: 82%;
  padding: 8px 12px;
  border-radius: 10px;
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}
.ball-msg.ai .ball-bubble { background: #fff; color: #18181b; border: 1px solid #ececec; }
.ball-msg.mine .ball-bubble { background: #18181b; color: #fff; }
.cursor { animation: blink 0.9s infinite; }
@keyframes blink { 50% { opacity: 0; } }

/* 动态衔接建议栏（放在输入框上方） */
.ball-sug-bar {
  display: flex; flex-wrap: wrap; gap: 5px;
  padding: 5px 12px; border-top: 1px solid #f0f1f3;
}
.ball-sug-chip {
  font-size: 11px;
  color: #52525b;
  background: #f0f1f3;
  border: 1px solid #e4e7ed;
  border-radius: 11px;
  padding: 3px 10px;
  cursor: pointer;
  transition: background 0.2s;
}
.ball-sug-chip:hover { background: #e4e5e7; }

.panel-input {
  display: flex;
  align-items: flex-end;
  gap: 8px;
  padding: 12px;
  border-top: 1px solid #ececec;
  background: #fff;
}
</style>