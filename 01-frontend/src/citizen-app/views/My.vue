<!--
  我的个人中心（极简风）：套 CitizenLayout 共享布局
-->
<template>
  <CitizenLayout title="个人中心">
    <!-- 1. 用户信息卡片 -->
    <section class="panel">
      <div class="user-row">
        <div class="avatar">{{ avatarLetter }}</div>
        <div class="user-info">
          <div class="user-name">
            {{ profile.name || auth.maskedPhone || '游客用户' }}
            <el-tag v-if="profile.registered" type="success" size="small" class="verify-tag">
              <el-icon class="tag-icon"><CircleCheck /></el-icon>
              已注册
            </el-tag>
          </div>
          <div class="user-sub">手机号 {{ profile.masked_phone || auth.maskedPhone || '— —' }}<template v-if="profile.email"> · {{ profile.email }}</template></div>
        </div>
      </div>
    </section>

    <!-- 1.5 个人资料（citizen_account，诉求提交默认值来源） -->
    <section class="panel" v-if="profile.registered">
      <div class="panel-head">
        <span class="panel-title">个人资料</span>
        <span class="panel-title-sm">注册于 {{ profile.create_time || '—' }}</span>
      </div>
      <el-descriptions :column="1" border size="small">
        <el-descriptions-item label="姓名">{{ profile.name || '—' }}</el-descriptions-item>
        <el-descriptions-item label="手机号">{{ profile.masked_phone || '—' }}</el-descriptions-item>
        <el-descriptions-item label="邮箱">{{ profile.email || '—' }}</el-descriptions-item>
        <el-descriptions-item label="辖区">{{ profile.district || '—' }}</el-descriptions-item>
        <el-descriptions-item label="街道">{{ profile.street || '—' }}</el-descriptions-item>
        <el-descriptions-item label="详细住址">{{ profile.address_detail || '—' }}</el-descriptions-item>
      </el-descriptions>
    </section>

    <!-- 2. 诉求统计卡片 -->
    <section class="panel">
      <div class="panel-head">
        <span class="panel-title">我的诉求统计</span>
        <el-link type="primary" underline="never" @click="goAppealList('all')">查看全部 →</el-link>
      </div>
      <div class="stats-grid">
        <div class="stat-cell" @click="goAppealList('processing')">
          <div class="stat-num stat-primary">{{ stats.processing }}</div>
          <div class="stat-label">处理中</div>
        </div>
        <div class="stat-cell" @click="goAppealList('closed')">
          <div class="stat-num stat-success">{{ stats.closed }}</div>
          <div class="stat-label">已办结</div>
        </div>
        <div class="stat-cell" @click="goAppealList('pending-eval')">
          <div class="stat-num stat-warning">{{ stats.pendingEval }}</div>
          <div class="stat-label">待评价</div>
        </div>
      </div>
      <div v-if="stats.processing === 0 && stats.closed === 0 && stats.pendingEval === 0" class="stats-empty">
        <el-icon :size="32" class="empty-icon"><Box /></el-icon>
        <div class="empty-text">您还没有提交过诉求</div>
        <el-button type="primary" round size="small" @click="goSubmit">去提交诉求</el-button>
      </div>
    </section>

    <!-- 3. 我的诉求业务 -->
    <section class="panel">
      <div class="panel-title-sm">我的诉求业务</div>
      <ul class="menu-list">
        <li class="menu-row" @click="goAppealList('all')">
          <el-icon class="menu-icon"><Document /></el-icon>
          <span class="menu-text">我的全部诉求</span>
          <span class="menu-extra" v-if="statsTotal > 0">{{ statsTotal }}</span>
          <el-icon class="menu-arrow"><ArrowRight /></el-icon>
        </li>
        <li class="menu-row" @click="goMessages">
          <el-icon class="menu-icon"><ChatDotRound /></el-icon>
          <span class="menu-text">我的消息</span>
          <span class="menu-extra danger" v-if="unreadCount > 0">{{ unreadCount }} 条未读</span>
          <el-icon class="menu-arrow"><ArrowRight /></el-icon>
        </li>
      </ul>
    </section>

    <!-- 4. 账号与设置 -->
    <section class="panel">
      <div class="panel-title-sm">账号与设置</div>
      <ul class="menu-list">
        <li class="menu-row" @click="goNotifySettings">
          <el-icon class="menu-icon"><Bell /></el-icon>
          <span class="menu-text">消息通知设置</span>
          <span class="menu-extra">{{ notifyEnabled ? '短信 + 站内' : '仅站内' }}</span>
          <el-icon class="menu-arrow"><ArrowRight /></el-icon>
        </li>
      </ul>
    </section>

    <!-- 5. 其他服务 -->
    <section class="panel">
      <div class="panel-title-sm">其他服务</div>
      <ul class="menu-list">
        <li class="menu-row" @click="callHotline">
          <el-icon class="menu-icon"><Phone /></el-icon>
          <span class="menu-text">12345 电话咨询</span>
          <span class="menu-extra">7×24 小时</span>
          <el-icon class="menu-arrow"><ArrowRight /></el-icon>
        </li>
      </ul>
    </section>
  </CitizenLayout>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  CircleCheck, Warning, ArrowRight,
  Document, ChatDotRound, Phone, Box, Bell
} from '@element-plus/icons-vue'
import CitizenLayout from '../components/CitizenLayout.vue'
import { useAuthStore } from '../stores/auth'
import { getUnreadCount } from '../api/message'
import { listMyAppeals } from '../api/appeal'
import { getCitizenProfile } from '../api/auth'

const router = useRouter()
const auth = useAuthStore()

/* ========== 头像字母 ========== */
const avatarLetter = computed(() => {
  const phone = auth.maskedPhone || ''
  return phone ? phone.replace(/[^0-9]/g, '').slice(-1) || '民' : '民'
})

/* ========== 未读消息数（真实接口） ========== */
const unreadCount = ref(0)

const loadUnread = async () => {
  try {
    const data = await getUnreadCount()
    unreadCount.value = data?.unread_count || 0
  } catch { unreadCount.value = 0 }
}

/* ========== 个人资料（citizen_account，个人中心展示） ========== */
const profile = ref({})
const loadProfile = async () => {
  if (!auth.maskedPhone) return   // 未登录不拉取
  try { profile.value = await getCitizenProfile() } catch { /* ignore */ }
}

/* ========== 诉求统计（真实接口，按状态聚合） ========== */
const stats = ref({ processing: 0, closed: 0, pendingEval: 0 })
const statsTotal = computed(() => stats.value.processing + stats.value.closed + stats.value.pendingEval)

/** 状态 → 统计分组 */
const loadStats = async () => {
  try {
    const data = await listMyAppeals(1, 100)
    const list = data?.list || []
    let processing = 0
    let closed = 0
    let pendingEval = 0
    for (const a of list) {
      const s = a.status
      if (s === 'CLOSED' || s === 'DONE') {
        closed += 1
        if (a.evaluated === 0) pendingEval += 1 // 已办结未评价 = 待评价
      } else if (s && s !== 'WITHDRAWN' && s !== 'DRAFT') {
        processing += 1 // 其余进行中状态
      }
    }
    stats.value = { processing, closed, pendingEval }
  } catch { /* 接口异常时保持零值 */ }
}

/* ========== 通知设置 ========== */
const notifyEnabled = ref(localStorage.getItem('citizen_notify_sms') === '1')

/* ========== 跳转 ========== */
const goMessages = () => router.push('/messages')
const goSubmit  = () => router.push('/submit-appeal')
const goNotifySettings = () => router.push('/messages?open=settings')

function goAppealList(status) {
  router.push({ path: '/my-appeals', query: { status } })
}

function callHotline() {
  ElMessage.info('请拨打 12345 政务服务热线（7×24 小时）')
  try { window.open('tel:12345') } catch { /* ignore */ }
}

onMounted(() => {
  loadUnread()
  loadStats()
  loadProfile()
  window.addEventListener('storage', () => {
    notifyEnabled.value = localStorage.getItem('citizen_notify_sms') === '1'
  })
})
</script>

<style scoped>
/* ========== 通用面板 ========== */
.panel {
  background: #fff;
  border-radius: 8px;
  border: 1px solid #ececec;
  padding: 16px 20px;
  margin-bottom: 14px;
}
.panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 14px;
  padding-bottom: 10px;
  border-bottom: 1px solid #f4f4f2;
}
.panel-title {
  font-size: 14px;
  font-weight: 600;
  color: #18181b;
  letter-spacing: 0.3px;
}
.panel-title-sm {
  font-size: 12px;
  font-weight: 600;
  color: #a1a1aa;
  text-transform: uppercase;
  letter-spacing: 0.8px;
  margin-bottom: 10px;
  padding: 0 4px;
}

/* ========== 用户信息行 ========== */
.user-row {
  display: flex;
  align-items: center;
  gap: 16px;
}
.avatar {
  width: 56px;
  height: 56px;
  border-radius: 14px;
  background: #18181b;
  color: #fff;
  font-size: 22px;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.user-info { flex: 1; min-width: 0; }
.user-name {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 16px;
  font-weight: 600;
  color: #18181b;
}
.user-name .tag-icon { margin-right: 2px; }
.user-sub { margin-top: 4px; font-size: 12.5px; color: #8e8e93; }

/* ========== 诉求统计 ========== */
.stats-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 12px;
}
.stat-cell {
  background: #fafafa;
  border: 1px solid #f0f0f0;
  border-radius: 8px;
  padding: 16px 12px;
  text-align: center;
  cursor: pointer;
  transition: border-color 0.15s, background 0.15s;
}
.stat-cell:hover { background: #fff; border-color: #d4d4d8; }
.stat-num { font-size: 26px; font-weight: 700; line-height: 1.1; color: #18181b; }
.stat-primary { color: #409eff; }
.stat-success { color: #67c23a; }
.stat-warning { color: #e6a23c; }
.stat-label { font-size: 12.5px; color: #8e8e93; margin-top: 4px; }

.stats-empty {
  margin-top: 14px;
  padding: 18px;
  text-align: center;
  background: #fafafa;
  border-radius: 8px;
}
.empty-icon { color: #a1a1aa; }
.empty-text { font-size: 13px; color: #8e8e93; margin: 6px 0 10px; }

/* ========== 菜单列表 ========== */
.menu-list {
  list-style: none;
  margin: 0;
  padding: 0;
}
.menu-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 4px;
  cursor: pointer;
  border-radius: 6px;
  transition: background 0.15s;
}
.menu-row + .menu-row { border-top: 1px solid #f4f4f2; }
.menu-row:hover { background: #fafafa; }

.menu-icon { font-size: 18px; color: #8e8e93; flex-shrink: 0; }
.menu-row:hover .menu-icon { color: #18181b; }
.menu-text { flex: 1; font-size: 14px; color: #18181b; }
.menu-extra { font-size: 12.5px; color: #a1a1aa; }
.menu-extra.danger { color: #f56c6c; font-weight: 600; }
.menu-arrow { color: #a1a1aa; font-size: 16px; }
.menu-row:hover .menu-arrow { color: #18181b; }
</style>
