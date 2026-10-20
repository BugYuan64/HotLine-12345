<!--
  市民端首页（与管理端同款极简风）：
  - 套 CitizenLayout 共享布局（白底侧边栏 + 极简 topbar）
  - 内容区：办理进度 / 通知公告 / 热门服务 三组卡片，黑灰白极简风
-->
<template>
  <CitizenLayout title="市民首页">
    <!-- 办理进度 -->
    <section class="panel">
      <div class="panel-head">
        <span class="panel-title">办理进度</span>
        <el-button link type="primary" @click="router.push('/my-appeals')">
          查看全部 <el-icon><ArrowRight /></el-icon>
        </el-button>
      </div>
      <div v-if="appealsLoading" class="empty-hint">加载中...</div>
      <div v-else-if="appeals.length === 0" class="empty-hint">暂无诉求记录</div>
      <div v-else class="card-grid">
        <div
          v-for="item in appeals"
          :key="item.id"
          class="mini-card"
          @click="router.push({ path: '/my-appeals', query: { appealNo: item.appealNo } })"
        >
          <div class="mc-head">
            <span class="mc-title">{{ item.title }}</span>
            <el-tag :type="bizTagType(item.bizStatus)" size="small" effect="plain">
              {{ statusText(item.bizStatus, item.departments) }}
            </el-tag>
          </div>
          <div class="mc-meta">
            <span>{{ item.appealNo }}</span>
            <span>{{ formatTime(item.createTime) }}</span>
          </div>
        </div>
      </div>
    </section>

    <!-- 通知公告 -->
    <section class="panel">
      <div class="panel-head">
        <span class="panel-title">通知公告</span>
        <el-button link type="primary" @click="router.push('/notices')">
          查看全部 <el-icon><ArrowRight /></el-icon>
        </el-button>
      </div>
      <div v-if="noticesLoading" class="empty-hint">加载中...</div>
      <div v-else-if="notices.length === 0" class="empty-hint">暂无公告</div>
      <div v-else class="card-grid">
        <div
          v-for="item in notices"
          :key="item.id"
          class="mini-card"
          @click="handleNoticeClick(item)"
        >
          <div class="mc-head">
            <span class="mc-title">
              <el-icon v-if="item.topFlag === 1" :size="13" style="color:#52525b;margin-right:4px"><Flag /></el-icon>
              {{ item.title }}
            </span>
          </div>
          <div class="mc-meta">
            <span>{{ formatTime(item.createTime) }}</span>
          </div>
        </div>
      </div>
    </section>

    <!-- 热门服务 -->
    <section class="panel">
      <div class="panel-head">
        <span class="panel-title">热门服务</span>
        <el-button link type="primary" @click="router.push('/submit-appeal')">
          去提交 <el-icon><ArrowRight /></el-icon>
        </el-button>
      </div>
      <div class="service-grid">
        <div
          v-for="svc in serviceItems"
          :key="svc.label"
          class="service-card"
          @click="router.push('/submit-appeal')"
        >
          <div class="service-icon">
            <el-icon :size="26"><component :is="svc.icon" /></el-icon>
          </div>
          <div class="service-label">{{ svc.label }}</div>
        </div>
      </div>
    </section>
  </CitizenLayout>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { formatTime } from '../../shared/format.js'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import CitizenLayout from '../components/CitizenLayout.vue'
import { ArrowRight, Flag } from '@element-plus/icons-vue'
import {
  OfficeBuilding, Van, FirstAidKit, Reading, Sunny, ScaleToOriginal
} from '@element-plus/icons-vue'
import { listMyAppeals, getPrimaries } from '../api/appeal'
import { getNoticeTop, getNoticeDetail } from '../api/notice'

const router = useRouter()

// ========== 诉求列表（办理进度） ==========
const appeals = ref([])
const appealsLoading = ref(false)

const loadAppeals = async () => {
  appealsLoading.value = true
  try {
    const data = await listMyAppeals(1, 10)
    appeals.value = (data.list || []).slice(0, 3)
  } catch { appeals.value = [] }
  finally { appealsLoading.value = false }
}

// ========== 公告列表 ==========
const notices = ref([])
const noticesLoading = ref(false)

const loadNotices = async () => {
  noticesLoading.value = true
  try {
    const data = await getNoticeTop(3)
    notices.value = (data || []).slice(0, 3)
  } catch { notices.value = [] }
  finally { noticesLoading.value = false }
}

// ========== 公告点击 ==========
const handleNoticeClick = async (notice) => {
  try {
    const detail = await getNoticeDetail(notice.id)
    ElMessage.info(`公告详情：${detail.title}`)
  } catch {
    ElMessage.info(notice.title)
  }
}

// ========== 热门服务 ==========
const serviceItems = [
  { icon: OfficeBuilding, label: '住房保障' },
  { icon: Van, label: '交通出行' },
  { icon: FirstAidKit, label: '医疗卫生' },
  { icon: Reading, label: '教育咨询' },
  { icon: Sunny, label: '环境保护' },
  { icon: ScaleToOriginal, label: '法律援助' }
]

// ========== 状态映射（与"办理进度"页一致：受理中/XX部门处理中/可评价/已评价/已办结/已撤回） ==========
const BIZ_MAP = {
  'DISPATCHING': { tagType: 'info' },
  'PROCESSING':  { tagType: 'primary' },
  'EVALUABLE':   { tagType: 'warning' },
  'EVALUATED':   { tagType: 'success' },
  'DONE':        { tagType: 'success' },
  'WITHDRAWN':   { tagType: 'info' }
}
const BIZ_TEXT = {
  'DISPATCHING': '受理中',
  'EVALUABLE': '可评价',
  'EVALUATED': '已评价',
  'DONE': '已办结',
  'WITHDRAWN': '已撤回'
}
const bizTagType = (s) => BIZ_MAP[s]?.tagType || 'info'

// 部门代码 → 中文部门名（classify_primary.department，如 URBAN → 城管部门）
const deptMap = ref({})
const statusText = (bizStatus, deptCodes) => {
  if (bizStatus === 'PROCESSING') {
    const names = (deptCodes || []).map((c) => deptMap.value[c] || '').filter(Boolean)
    return (names.length ? names.join('、') : '') + '处理中'
  }
  return BIZ_TEXT[bizStatus] || '处理中'
}
onMounted(async () => {
  try {
    const list = await getPrimaries()
    deptMap.value = Object.fromEntries((list || []).map((p) => [p.code, p.department || p.name]))
  } catch { deptMap.value = {} }
  loadAppeals()
  loadNotices()
})
</script>

<style scoped>
/* 三个面板（主要内容卡片：#FAFAFA） */
.panel {
  background: #fafafa;
  border-radius: 8px;
  border: 1px solid #ececec;
  padding: 16px 20px;
  margin-bottom: 16px;
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
.empty-hint {
  padding: 28px;
  text-align: center;
  color: #8e8e93;
  font-size: 13px;
}

/* 通用卡片网格（2 列） */
.card-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: 12px;
}
.mini-card {
  border: 1px solid #f0f0f0;
  border-radius: 6px;
  padding: 12px 14px;
  cursor: pointer;
  transition: border-color 0.15s, box-shadow 0.15s;
}
.mini-card:hover {
  border-color: #d4d4d8;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.05);
}
.mc-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}
.mc-title {
  font-size: 13.5px;
  font-weight: 500;
  color: #18181b;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  flex: 1;
  min-width: 0;
}
.mc-meta {
  margin-top: 6px;
  font-size: 12px;
  color: #a1a1aa;
  display: flex;
  gap: 12px;
}

/* 热门服务网格（3 列） */
.service-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(140px, 1fr));
  gap: 12px;
}
.service-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 20px 8px;
  border: 1px solid #f0f0f0;
  border-radius: 6px;
  cursor: pointer;
  transition: border-color 0.15s, background 0.15s;
}
.service-card:hover {
  border-color: #18181b;
  background: #fafafa;
}
.service-icon {
  width: 48px;
  height: 48px;
  border-radius: 12px;
  background: #f4f4f2;
  color: #18181b;
  display: flex;
  align-items: center;
  justify-content: center;
}
.service-label {
  font-size: 13px;
  color: #52525b;
  font-weight: 500;
}
</style>
