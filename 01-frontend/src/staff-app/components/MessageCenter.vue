<!--
  消息中心内容组件（部门端/街道办端共用）：
  纯内容，不含布局。api 由父级薄壳页注入（各端各传各的 message api），
  避免在组件内写任何 staff/street 专属逻辑。
-->
<template>
  <div class="message-center">
    <!-- 操作栏 -->
    <div class="action-bar">
      <el-button link type="primary" @click="handleMarkAllRead" :disabled="!unreadCount">
        <el-icon><Checked /></el-icon> 全部已读
      </el-button>
    </div>

    <!-- 未读提示 -->
    <div v-if="unreadCount > 0" class="unread-banner">
      您有 {{ unreadCount }} 条未读消息
    </div>

    <!-- 消息列表 -->
    <div class="message-list" v-loading="loading">
      <el-empty v-if="!loading && messages.length === 0" description="暂无消息" />

      <div
        v-for="msg in messages"
        :key="msg.id"
        class="message-item"
        :class="{ unread: msg.isRead === 0 }"
        @click="handleRead(msg)"
      >
        <div class="msg-icon-wrap">
          <el-icon class="msg-icon" :size="20">
            <Bell v-if="msg.msgType === 'WARNING'" />
            <Warning v-else-if="msg.msgType === 'ORDER_RETURNED'" />
            <Document v-else-if="msg.msgType === 'ORDER_CREATED' || msg.msgType === 'ORDER_DISPATCHED'" />
            <CircleCheck v-else-if="msg.msgType === 'ORDER_CLOSED'" />
            <Connection v-else />
          </el-icon>
        </div>
        <div class="msg-body">
          <div class="msg-top">
            <el-tag :type="tagTypeOf(msg.msgType)" size="small" effect="plain">
              {{ msgTypeText(msg.msgType) }}
            </el-tag>
            <span class="msg-title">{{ msg.title }}</span>
            <span v-if="msg.isRead === 0" class="unread-dot"></span>
          </div>
          <div class="msg-content">{{ msg.content }}</div>
          <div class="msg-time">{{ msg.createTime }}</div>
        </div>
      </div>
    </div>

    <div class="pagination-wrap" v-if="total > pageSize">
      <el-pagination
        background
        layout="prev, pager, next"
        :total="total"
        :page-size="pageSize"
        :current-page="page"
        @current-change="load"
      />
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Bell, CircleCheck, Warning, Document, Connection, Checked } from '@element-plus/icons-vue'

const props = defineProps({
  api: { type: Object, required: true }
})

const loading = ref(false)
const messages = ref([])
const total = ref(0)
const page = ref(1)
const pageSize = 20
const unreadCount = ref(0)

const MSG_TYPE_TEXT = {
  ORDER_CREATED: '诉求受理', ORDER_DISPATCHED: '工单分派',
  ORDER_RETURNED: '工单退回', ORDER_CLOSED: '已办结',
  ORDER_REOPENED: '工单重开', WARNING: '预警'
}
const TAG_TYPE = {
  ORDER_CREATED: 'primary', ORDER_DISPATCHED: 'info',
  ORDER_RETURNED: 'warning', ORDER_CLOSED: 'success',
  ORDER_REOPENED: 'danger', WARNING: 'danger'
}
const msgTypeText = (type) => MSG_TYPE_TEXT[type] || type
const tagTypeOf = (type) => TAG_TYPE[type] || 'info'

const load = async (p = 1) => {
  page.value = p
  loading.value = true
  try {
    const data = await props.api.getMessages(page.value, pageSize)
    messages.value = data.list || []
    total.value = data.total || 0
    unreadCount.value = data.unread_count || 0
    // 进入消息中心即全部已读（静默）：侧边栏徽标随之清零（四端消息红点统一逻辑）
    if (unreadCount.value > 0) {
      try {
        await props.api.markAllRead()
        messages.value.forEach(m => m.isRead = 1)
        unreadCount.value = 0
      } catch { /* ignore */ }
    }
    window.dispatchEvent(new Event('unread:refresh'))
  } catch { /* handled by interceptor */ }
  finally { loading.value = false }
}

const handleRead = async (msg) => {
  if (msg.isRead === 0) {
    try {
      await props.api.markRead(msg.id)
      msg.isRead = 1
      unreadCount.value = Math.max(0, unreadCount.value - 1)
      window.dispatchEvent(new Event('unread:refresh'))
    } catch { /* ignore */ }
  }
}

const handleMarkAllRead = async () => {
  try {
    await props.api.markAllRead()
    messages.value.forEach(m => m.isRead = 1)
    unreadCount.value = 0
    ElMessage.success('已全部标记为已读')
  } catch { /* ignore */ }
}

onMounted(() => load())
</script>

<style scoped>
/* 操作栏 */
.action-bar {
  display: flex;
  justify-content: flex-end;
  gap: 4px;
  margin-bottom: 12px;
}

/* 未读提示 */
.unread-banner {
  background: #fef0f0;
  border: 1px solid #fde2e2;
  border-radius: 6px;
  padding: 10px 16px;
  margin-bottom: 12px;
  color: #f56c6c;
  font-size: 13px;
}

/* 消息列表 */
.message-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.message-item {
  display: flex;
  gap: 14px;
  background: #fff;
  border: 1px solid #ececec;
  border-radius: 8px;
  padding: 16px;
  cursor: pointer;
  transition: border-color 0.15s, box-shadow 0.15s;
}
.message-item:hover { border-color: #d4d4d8; box-shadow: 0 1px 4px rgba(0,0,0,0.05); }
.message-item.unread { background: #fafafa; border-color: #18181b; }

.msg-icon-wrap {
  width: 40px; height: 40px;
  border-radius: 10px;
  background: #f4f4f2;
  color: #18181b;
  display: flex; align-items: center; justify-content: center;
  flex-shrink: 0;
}
.msg-icon { color: inherit; }

.msg-body { flex: 1; min-width: 0; }
.msg-top { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.msg-title { font-size: 14px; font-weight: 500; color: #18181b; }
.unread-dot { width: 8px; height: 8px; border-radius: 50%; background: #8fd1a3; flex-shrink: 0; }
.msg-content {
  font-size: 13px; color: #52525b; margin-top: 6px; line-height: 1.5;
  overflow: hidden; text-overflow: ellipsis;
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical;
}
.msg-time { font-size: 12px; color: #8e8e93; margin-top: 4px; }

.pagination-wrap { display: flex; justify-content: center; padding: 16px; }
</style>