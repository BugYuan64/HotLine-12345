<!--
  智能助手聊天页（RAG 流式问答）：
  - 套 CitizenLayout 共享布局（与管理端同款极简风）
  - 聊天气泡 + SSE 逐字渲染 + 知识来源展示 + 多轮对话
-->
<template>
  <CitizenLayout title="智能助手">
    <!-- 聊天主区域 -->
    <div class="chat-panel">
      <!-- 消息列表 -->
      <div ref="listRef" class="msg-list">
        <!-- 顶部操作条：清空聊天记录（用户手动清除） -->
        <div class="chat-toolbar">
          <span class="toolbar-hint">{{ messages.length > 0 ? `共 ${messages.length} 条消息` : '' }}</span>
          <el-button
            v-if="messages.length > 0"
            link
            type="danger"
            class="clear-btn"
            @click="handleClear"
          >
            <el-icon><Delete /></el-icon> 清空聊天记录
          </el-button>
        </div>

        <!-- 欢迎语 + 快捷问题（空状态） -->
        <div v-if="messages.length === 0" class="welcome">
          <div class="welcome-icon"><el-icon :size="30"><ChatDotRound /></el-icon></div>
          <div class="welcome-title">您好，我是智能助手小政</div>
          <div class="welcome-sub">基于知识库为您解答 12345 热线相关问题，也可以直接下方提问</div>
          <div class="quick-list">
            <div
              v-for="q in quickQuestions"
              :key="q"
              class="quick-chip"
              @click="send(q)"
            >{{ q }}</div>
            <el-button link type="primary" class="quick-more" @click="send('查看全部推荐问题')">试试查看全部</el-button>
          </div>
        </div>

        <!-- 气泡消息 -->
        <div
          v-for="(msg, i) in messages"
          :key="i"
          class="msg-row"
          :class="msg.role === 'user' ? 'mine' : 'ai'"
        >
          <!-- AI 头像 -->
          <div v-if="msg.role === 'assistant'" class="ai-avatar">
            <el-icon :size="16"><ChatDotRound /></el-icon>
          </div>
          <div :class="msg.role === 'assistant' ? 'ai-main' : 'mine-wrap'">
            <div v-if="msg.tools && msg.tools.length" style="margin-bottom:6px;display:flex;flex-wrap:wrap;gap:6px;">
                <span v-for="(t, ti) in msg.tools" :key="ti" style="font-size:11px;color:#8a8f98;background:#f0f1f3;border-radius:9px;padding:2px 8px;">{{ t.phase === 'end' ? '✅' : '🔍' }} {{ t.name }}</span>
              </div>
            <div class="bubble" :class="{ streaming: msg.streaming }">
              <div class="bubble-text">{{ msg.content }}<span v-if="msg.streaming" class="cursor">▍</span></div>
              <!-- 知识来源（RAG 检索依据，可折叠） -->
              <div v-if="msg.sources && msg.sources.length" class="sources">
                <div class="sources-toggle" @click="msg.showSources = !msg.showSources">
                  <el-icon :size="12"><component :is="msg.showSources ? ArrowUp : ArrowDown" /></el-icon>
                  参考依据（{{ msg.sources.length }} 条知识库文档）
                </div>
                <div v-show="msg.showSources" class="sources-list">
                  <div v-for="(s, si) in msg.sources" :key="si" class="source-item">
                    <span class="source-title">{{ s.title }}</span>
                    <span class="source-sim" v-if="s.similarity">相关度 {{ Math.round(s.similarity * 100) }}%</span>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- 动态衔接建议（放在输入框上方，仅显示最新一轮 AI 建议） -->
      <div v-if="currentSuggestions.length" class="sug-bar">
        <span v-for="(s, i) in currentSuggestions" :key="i" class="sug-chip" @click="send(s)">{{ s }} →</span>
      </div>

      <!-- 输入区 -->
      <div class="input-bar">
        <el-input
          v-model="input"
          type="textarea"
          :rows="2"
          resize="none"
          maxlength="500"
          placeholder="请输入您的问题，Enter 发送（Shift+Enter 换行）"
          :disabled="sending"
          @keydown.enter.exact.prevent="onEnter"
        />
        <el-button
          type="primary"
          class="send-btn"
          :loading="sending"
          :disabled="!input.trim()"
          @click="send()"
        >发送</el-button>
      </div>
      <div class="disclaimer">内容由 AI 基于知识库生成，仅供参考；紧急情况请拨打 110 / 119 / 120</div>
    </div>
  </CitizenLayout>
</template>

<script setup>
import { computed, nextTick, onMounted, onUnmounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowDown, ArrowRight, ArrowUp, ChatDotRound, Delete } from '@element-plus/icons-vue'
import CitizenLayout from '../components/CitizenLayout.vue'
import { streamChat, loadChatHistory, saveChatHistory, clearChatHistory } from '../api/chat'
import { readCitizenToken } from '../stores/auth'

const SCOPE = 'citizen' // 知识库作用域：一个端一个知识库

/** 快捷问题（常见咨询入口）：2条工具查询 + 2条知识咨询 + 1条填写引导 */
const quickQuestions = [
  '帮我查一下我的诉求办理进度',
  '我提交过哪些诉求？',
  '诉求提交后AI是怎么处理的？',
  '怎么写诉求处理得更快？',
  '对办理结果不满意怎么办？'
]

/** 消息列表：{role, content, streaming, sources, showSources} */
const messages = reactive([])
const input = ref('')
const sending = ref(false)
const currentSuggestions = ref([])
const listRef = ref(null)
let abortFn = null

/** 恢复本地持久化的聊天记录（切换页面不丢失） */
const restoreHistory = () => {
  const saved = loadChatHistory(SCOPE)
  for (const m of saved) {
    messages.push(reactive({
      role: m.role,
      content: m.content,
      streaming: false,
      sources: m.sources || null,
      showSources: false
    }))
  }
}

/** 手动清空聊天记录（同时清理本地缓存） */
const handleClear = async () => {
  try {
    await ElMessageBox.confirm('确定清空所有聊天记录吗？此操作不可恢复。', '清空聊天记录', {
      confirmButtonText: '清空',
      cancelButtonText: '取消',
      type: 'warning'
    })
  } catch { return } // 用户取消
  if (abortFn) abortFn() // 中断进行中的流
  messages.splice(0)
  clearChatHistory(SCOPE)
  ElMessage.success('聊天记录已清空')
}

/** 滚动到底部 */
const scrollToBottom = () => {
  nextTick(() => {
    if (listRef.value) listRef.value.scrollTop = listRef.value.scrollHeight
  })
}

/** Enter 发送 */
const onEnter = () => {
  if (!sending.value && input.value.trim()) send()
}

/** 发送问题（流式接收回答） */
const send = (preset) => {
  const question = (preset || input.value).trim()
  if (!question || sending.value) return
  input.value = ''
  sending.value = true
  currentSuggestions.value = []

  // 用户消息 + AI 占位气泡（streaming 状态显示打字光标）
  messages.push({ role: 'user', content: question })
  const aiMsg = reactive({ role: 'assistant', content: '', streaming: true, sources: null, showSources: false, tools: [], suggestions: [] })
  messages.push(aiMsg)
  scrollToBottom()

  // 携带最近 6 条历史（3 轮）维持上下文
  const history = messages
    .filter(m => !m.streaming && (m.role === 'user' || m.role === 'assistant'))
    .slice(-6)
    .map(m => ({ role: m.role, content: m.content }))

  abortFn = streamChat(
    { question, history, scope: SCOPE },
    (evt) => {
      if (evt.type === 'sources') {
        aiMsg.sources = evt.sources || []
      } else if (evt.type === 'tool') {
        if (!aiMsg.tools) aiMsg.tools = []
        aiMsg.tools.push({ name: evt.name || '', phase: evt.phase })
        scrollToBottom()
      } else if (evt.type === 'delta') {
        aiMsg.content += evt.content // 逐字渲染
        scrollToBottom()
      } else if (evt.type === 'suggestions') {
        // 动态衔接建议：刷新到全局 currentSuggestions，显示在输入框上方
        currentSuggestions.value = evt.suggestions || []
      } else if (evt.type === 'error') {
        aiMsg.content += aiMsg.content ? '\n\n（回答中断）' : `抱歉，${evt.message}`
        ElMessage.error(evt.message || '智能助手开小差了')
      }
      // done 事件在 onDone 中统一收尾
    },
    () => {
      aiMsg.streaming = false
      sending.value = false
      abortFn = null
      if (!aiMsg.content) {
        aiMsg.content = '抱歉，智能助手暂时无法回答，请拨打 12345 热线人工咨询。'
      }
      saveChatHistory(SCOPE, messages) // 每轮回答完成后持久化
      scrollToBottom()
    },
    { getToken: readCitizenToken }
  )
}

onMounted(() => {
  restoreHistory()
  // 页面关闭 / 切换路由前持久化当前记录
  window.addEventListener('beforeunload', persist)
})

const persist = () => saveChatHistory(SCOPE, messages)

onUnmounted(() => {
  if (abortFn) abortFn() // 离开页面中断流
  window.removeEventListener('beforeunload', persist)
  saveChatHistory(SCOPE, messages) // 离开页面立即保存
})
</script>

<style scoped>
/* 聊天面板（占满内容区高度） */
.chat-panel {
  display: flex;
  flex-direction: column;
  height: calc(100vh - 52px - 40px); /* 减 topbar 与内容区 padding */
  background: #fff;
  border: 1px solid #ececec;
  border-radius: 8px;
  overflow: hidden;
}

/* 消息列表 */
.msg-list {
  flex: 1;
  overflow-y: auto;
  padding: 20px;
}
.msg-list::-webkit-scrollbar { width: 4px; }
.msg-list::-webkit-scrollbar-thumb { background: #e5e5e5; border-radius: 2px; }

/* 顶部操作条 */
.chat-toolbar {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 10px;
  margin-bottom: 10px;
}
.toolbar-hint { font-size: 12px; color: #a1a1aa; }
.clear-btn { font-size: 12.5px; }

/* 欢迎空状态 */
.welcome {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding-top: 8vh;
  text-align: center;
}
.welcome-icon {
  width: 64px;
  height: 64px;
  border-radius: 16px;
  background: #18181b;
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 14px;
}
.welcome-title { font-size: 18px; font-weight: 600; color: #18181b; }
.welcome-sub { font-size: 13px; color: #8e8e93; margin-top: 6px; }
.quick-list {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 10px;
  margin-top: 22px;
}
.quick-chip {
  padding: 8px 16px;
  border: 1px solid #e5e5e5;
  border-radius: 18px;
  font-size: 13px;
  color: #52525b;
  cursor: pointer;
  transition: all 0.15s;
}
.quick-chip:hover { border-color: #18181b; color: #18181b; background: #fafafa; }
.quick-more { font-size: 12px; }

/* 气泡行 */
.msg-row {
  display: flex;
  gap: 10px;
  margin-bottom: 16px;
}
.msg-row.mine { justify-content: flex-end; }
.ai-avatar {
  width: 32px;
  height: 32px;
  border-radius: 8px;
  background: #18181b;
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  margin-top: 2px;
}

/* 气泡 */
.bubble {
  max-width: 72%;
  padding: 10px 14px;
  border-radius: 10px;
  font-size: 14px;
  line-height: 1.65;
  white-space: pre-wrap;
  word-break: break-word;
}
.msg-row.ai .bubble { background: #f4f4f2; color: #18181b; border-top-left-radius: 2px; }
.msg-row.mine .bubble { background: #18181b; color: #fff; border-top-right-radius: 2px; }

/* AI 消息列（气泡 + 相关追问建议）；mine-wrap 透明化保持用户气泡右对齐 */
.ai-main { display: flex; flex-direction: column; align-items: flex-start; max-width: 72%; }
.ai-main .bubble { max-width: 100%; }
.mine-wrap { display: contents; }

/* 相关追问建议胶囊（回答完成后出现，点击直接发送） */
/* 动态衔接建议栏（放在输入框上方） */
.sug-bar {
  display: flex; flex-wrap: wrap; gap: 6px;
  padding: 8px 16px; border-top: 1px solid #f0f1f3;
}
.sug-chip {
  font-size: 12px;
  color: #52525b;
  background: #f0f1f3;
  border: 1px solid #e4e7ed;
  border-radius: 13px;
  padding: 4px 12px;
  cursor: pointer;
  transition: background 0.2s;
}
.sug-chip:hover { background: #e4e5e7; }

/* 打字光标 */
.cursor { animation: blink 0.9s infinite; color: #71717a; }
@keyframes blink { 50% { opacity: 0; } }

/* 知识来源折叠 */
.sources { margin-top: 8px; border-top: 1px dashed #d4d4d8; padding-top: 6px; }
.sources-toggle {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  color: #8e8e93;
  cursor: pointer;
}
.sources-toggle:hover { color: #18181b; }
.sources-list { margin-top: 6px; }
.source-item {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: #52525b;
  padding: 3px 0;
}
.source-title { font-weight: 500; }
.source-sim { color: #a1a1aa; }

/* 输入区 */
.input-bar {
  display: flex;
  gap: 10px;
  padding: 12px 16px;
  border-top: 1px solid #ececec;
  align-items: flex-end;
}
.send-btn { min-width: 84px; height: 54px; }
.disclaimer {
  padding: 0 16px 10px;
  font-size: 11.5px;
  color: #a1a1aa;
  text-align: center;
}
</style>