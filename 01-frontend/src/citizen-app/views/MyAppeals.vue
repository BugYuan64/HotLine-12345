<!--
  我的全部诉求（极简风）：套 CitizenLayout 共享布局
-->
<template>
  <CitizenLayout title="办理进度">
    <!-- 操作工具栏：状态筛选 + 编号搜索 + 新提交入口（单行，窄屏自动换行） -->
    <div class="toolbar">
      <el-select
        v-model="filterStatus"
        placeholder="办理状态"
        clearable
        style="width: 140px"
        @change="handleFilterChange"
      >
        <el-option label="全部" value="" />
        <el-option label="受理中" value="DISPATCHING" />
        <el-option label="处理中" value="PROCESSING" />
        <el-option label="可评价" value="EVALUABLE" />
        <el-option label="已评价" value="EVALUATED" />
        <el-option label="已办结" value="DONE" />
        <el-option label="已撤回" value="WITHDRAWN" />
      </el-select>

      <el-input
        v-model="searchNo"
        placeholder="诉求编号"
        style="width: 240px"
        clearable
        @keyup.enter="handleSearch"
      />

      <el-button type="primary" :loading="loading" @click="handleSearch">查询</el-button>
      <el-button @click="handleClearSearch">清空</el-button>

      <el-button type="primary" class="toolbar-right" @click="router.push('/submit-appeal')">
        <el-icon><Edit /></el-icon> 新提交诉求
      </el-button>
    </div>

    <!-- 敏感事件列表（剥离模式诉求快照：专人专线受理，仅本人可见进度） -->
    <div class="sensitive-section" v-if="sensitiveList.length">
      <div class="section-head">
        <span class="section-title">敏感事件</span>
        <span class="section-tip">已转专人专线受理，处理进度如下</span>
      </div>
      <div class="appeals-list">
        <div
          v-for="item in sensitiveList"
          :key="item.appeal_no"
          class="appeal-card sensitive-card"
          @click="openSensitiveDialog(item)"
        >
          <div class="card-head">
            <div class="card-no">{{ item.appeal_no }}</div>
            <el-tag :type="item.handle_status === 'HANDLED' ? 'success' : 'warning'" size="small" effect="plain">
              {{ item.handle_status === 'HANDLED' ? '已处理' : '处理中' }}
            </el-tag>
          </div>
          <div class="card-content">{{ item.content }}</div>
          <div class="card-foot">
            <span><el-icon :size="13"><Clock /></el-icon>{{ formatTime(item.trigger_time) }}</span>
            <span v-if="item.district">
              <el-icon :size="13"><LocationInformation /></el-icon>
              {{ item.district }}{{ item.street ? ' · ' + item.street : '' }}
            </span>
          </div>
        </div>
      </div>
    </div>

    <!-- 诉求列表 -->
    <div class="appeals-list" v-loading="loading">
      <el-empty v-if="!loading && appeals.length === 0" description="暂无诉求记录" :image-size="80" />

      <div
        v-for="item in appeals"
        :key="item.id"
        class="appeal-card"
        @click="openProgressDialog(item.appealNo)"
      >
        <div class="card-head">
          <div class="card-no">{{ item.appealNo }}</div>
          <el-tag :type="bizTagType(item.bizStatus)" size="small" effect="plain">
            {{ statusText(item.bizStatus, item.departments) }}
          </el-tag>
          <!-- 删除按钮：诉求全部评价（或处置满 7 天超评价期）后可用；软删除仅系统留档，列表不再展示 -->
          <el-button
            class="card-delete"
            size="small"
            type="danger"
            effect="plain"
            :disabled="!item.deletable"
            :loading="deleting === item.appealNo"
            :title="item.deletable ? '' : '已评价或街道办处置满7天（超评价期）后可删除'"
            @click.stop="handleDelete(item)"
          >删除</el-button>
        </div>
        <div class="card-title">{{ item.title }}</div>
        <div class="card-content">{{ item.content }}</div>
        <!-- 拆分后各子诉求独立状态（部门处理中/可评价/已评价/已办结） -->
        <div v-if="(item.children || []).length" class="child-list">
          <div v-for="c in item.children" :key="c.appealNo" class="child-row">
            <span class="child-no">{{ c.appealNo }}</span>
            <span class="child-title">{{ c.title || c.content }}</span>
            <el-tag :type="bizTagType(c.bizStatus)" size="small" effect="plain">
              {{ statusText(c.bizStatus, c.department ? [c.department] : []) }}
            </el-tag>
          </div>
        </div>
        <div class="card-foot">
          <span><el-icon :size="13"><Clock /></el-icon>{{ formatTime(item.createTime) }}</span>
          <span v-if="item.district">
            <el-icon :size="13"><LocationInformation /></el-icon>
            {{ item.district }}{{ item.street ? ' · ' + item.street : '' }}
          </span>
        </div>
      </div>
    </div>

    <!-- 分页 -->
    <div class="pagination-wrap" v-if="total > pageSize">
      <el-pagination
        background
        layout="prev, pager, next, total"
        :total="total"
        :page-size="pageSize"
        :current-page="page"
        @current-change="handlePageChange"
      />
    </div>

    <!-- 诉求进度弹窗（右上角 X 关闭） -->
    <AppealProgressDialog v-model="dialogVisible" :appeal-no="currentAppealNo" />

    <!-- 敏感事件详情弹窗（剥离诉求快照：编号/内容/地址/时间/处理状态） -->
    <el-dialog v-model="sensitiveDialogVisible" title="敏感事件详情" width="min(560px, 92vw)" align-center>
      <el-descriptions v-if="currentSensitive" :column="1" border size="small">
        <el-descriptions-item label="事件编号">{{ currentSensitive.appeal_no }}</el-descriptions-item>
        <el-descriptions-item label="提交时间">{{ formatTime(currentSensitive.trigger_time) || '-' }}</el-descriptions-item>
        <el-descriptions-item label="处理状态">
          <el-tag :type="currentSensitive.handle_status === 'HANDLED' ? 'success' : 'warning'" size="small">
            {{ currentSensitive.handle_status === 'HANDLED' ? '已处理' : '处理中' }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="诉求内容">{{ currentSensitive.content || '-' }}</el-descriptions-item>
        <el-descriptions-item label="地址">
          {{ [currentSensitive.district, currentSensitive.street, currentSensitive.address_detail].filter(Boolean).join(' ') || '全市范围' }}
        </el-descriptions-item>
      </el-descriptions>
      <div class="sensitive-dialog-tip">该诉求已转专人专线受理，处理完成后状态将更新为"已处理"</div>
    </el-dialog>
  </CitizenLayout>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { formatTime } from '../../shared/format.js'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Edit, Clock, LocationInformation } from '@element-plus/icons-vue'
import CitizenLayout from '../components/CitizenLayout.vue'
import AppealProgressDialog from '../components/AppealProgressDialog.vue'
import { listMyAppeals, listMySensitiveEvents, getPrimaries, deleteAppeal } from '../api/appeal'

const router = useRouter()
const route = useRoute()

// ========== 诉求进度弹窗 ==========
const dialogVisible = ref(false)
const currentAppealNo = ref('')

const openProgressDialog = (appealNo) => {
  currentAppealNo.value = appealNo
  dialogVisible.value = true
}

// ========== 敏感事件详情弹窗 ==========
const sensitiveDialogVisible = ref(false)
const currentSensitive = ref(null)

const openSensitiveDialog = (item) => {
  currentSensitive.value = item
  sensitiveDialogVisible.value = true
}

// ========== 业务状态映射（后端聚合推导：受理中/XX部门处理中/可评价/已评价/已办结/已撤回） ==========
const BIZ_MAP = {
  'DISPATCHING': { tagType: 'info' },     // 受理中（未分派/未建单）
  'PROCESSING':  { tagType: 'primary' },  // XX部门处理中（部门名由前端按代码翻译）
  'EVALUABLE':   { tagType: 'warning' },  // 街道办处理完毕，可评价
  'EVALUATED':   { tagType: 'success' },  // 已评价
  'DONE':        { tagType: 'success' },  // 已办结/归档
  'WITHDRAWN':   { tagType: 'info' }      // 已撤回
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
const loadDeptMap = async () => {
  try {
    const list = await getPrimaries()
    deptMap.value = Object.fromEntries((list || []).map((p) => [p.code, p.department || p.name]))
  } catch { deptMap.value = {} }
}
/** 状态文案：处理中显示"XX部门处理中"（多部门用、分隔），其余为固定文案；不暴露部门代码 */
const statusText = (bizStatus, deptCodes) => {
  if (bizStatus === 'PROCESSING') {
    const names = (deptCodes || []).map((c) => deptMap.value[c] || '').filter(Boolean)
    return (names.length ? names.join('、') : '') + '处理中'
  }
  return BIZ_TEXT[bizStatus] || '处理中'
}

// ========== 数据加载 ==========
const appeals = ref([])
const loading = ref(false)
const page = ref(1)
const pageSize = ref(10)
const total = ref(0)
const filterStatus = ref('')
const searchNo = ref('')

// 敏感事件列表（剥离诉求快照）：与诉求列表同页展示，单账号量少取前 50 条不分页
const sensitiveList = ref([])

// 加载敏感事件（编号搜索时同步过滤：SE 前缀编号在诉求列表查不到）
const fetchSensitive = async (no) => {
  try {
    const data = await listMySensitiveEvents(1, 50, no || undefined)
    sensitiveList.value = data.list || []
  } catch { sensitiveList.value = [] }
}

// 诉求编号查询：编号走后端精确匹配；业务状态筛选走后端取 50 条后前端过滤
// （市民个人诉求量远小于 50，避免只过滤当前页导致跨页数据漏查、分页 total 错乱）
const fetchAppeals = async () => {
  loading.value = true
  // 敏感事件与诉求列表同步加载（搜索编号时两边都按编号过滤）
  const no = searchNo.value.trim()
  fetchSensitive(no)
  try {
    if (no) {
      // 编号后端匹配：单条场景，直接取结果
      const data = await listMyAppeals(1, 10, no)
      appeals.value = data.list || []
      total.value = appeals.value.length
    } else {
      const filtering = !!filterStatus.value
      const data = await listMyAppeals(filtering ? 1 : page.value, filtering ? 50 : pageSize.value)
      let list = data.list || []
      if (filtering) {
        list = list.filter(a => a.bizStatus === filterStatus.value)
      }
      appeals.value = list
      total.value = filtering ? list.length : (data.total || 0)
    }
  } catch { appeals.value = [] }
  finally { loading.value = false }
}

const handleSearch = () => {
  page.value = 1
  fetchAppeals()
}

// 清空重新加载（保留原交互习惯）
const handleClearSearch = () => {
  searchNo.value = ''
  page.value = 1
  fetchAppeals()
}

const handlePageChange = (p) => { page.value = p; fetchAppeals() }
const handleFilterChange = () => {
  filterStatus.value = filterStatus.value || '' // clearable 清空后归一化为"全部"
  page.value = 1
  fetchAppeals()
}

// ========== 删除诉求（软删除：仅系统留档，列表不再展示） ==========
const deleting = ref('') // 正在删除的诉求编号
const handleDelete = async (item) => {
  try {
    await ElMessageBox.confirm(
      `删除后「${item.appealNo}」将从诉求列表移除，仅系统保留记录。确定删除？`,
      '删除诉求',
      { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' }
    )
  } catch { return } // 取消删除
  deleting.value = item.appealNo
  try {
    await deleteAppeal(item.appealNo)
    ElMessage.success('诉求已删除')
    fetchAppeals()
  } catch { /* 拦截器已提示（不满足删除条件等） */ } finally {
    deleting.value = ''
  }
}

onMounted(() => {
  loadDeptMap()
  fetchAppeals()
  // 从首页/提交成功页跳转携带诉求编号：自动打开对应诉求的进度弹窗
  if (route.query.appealNo) {
    openProgressDialog(String(route.query.appealNo))
  }
})
</script>

<style scoped>
.toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;          /* 窄屏（手机）自动换行，不挤压变形 */
  padding: 14px 18px;
  background: #fff;
  border: 1px solid #ececec;
  border-radius: 8px;
  margin-bottom: 14px;
}
.toolbar-right { margin-left: auto; }   /* 新提交诉求始终靠最右，与左侧筛选区分 */
/* 敏感事件区块：橙色左边条与"处理中"警示色呼应，和普通诉求区分 */
.sensitive-section { margin-bottom: 14px; }
.section-head { display: flex; align-items: baseline; gap: 10px; margin-bottom: 10px; }
.section-title { font-size: 15px; font-weight: 600; color: #18181b; }
.section-tip { font-size: 12px; color: #8e8e93; }
.sensitive-card { border-left: 3px solid #e6a23c; }
.sensitive-dialog-tip { color: var(--el-text-color-secondary); font-size: 12px; margin-top: 12px; }
.appeals-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.appeal-card {
  background: #fff;
  border: 1px solid #ececec;
  border-radius: 8px;
  padding: 16px 18px;
  cursor: pointer;
  transition: border-color 0.15s, box-shadow 0.15s;
}
.appeal-card:hover { border-color: #d4d4d8; box-shadow: 0 1px 4px rgba(0,0,0,0.05); }
.card-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
}
/* 删除按钮固定在卡片头部最右（不可用时悬停 title 提示原因） */
.card-delete { margin-left: auto; }
.card-no {
  font-size: 12px;
  color: #a1a1aa;
  font-family: monospace;
}
.card-title {
  font-size: 14px;
  font-weight: 600;
  color: #18181b;
  margin-bottom: 6px;
}
.card-content {
  font-size: 13px;
  color: #52525b;
  line-height: 1.5;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  margin-bottom: 10px;
}
.card-foot {
  display: flex;
  gap: 18px;
  font-size: 12px;
  color: #8e8e93;
}
.card-foot span { display: inline-flex; align-items: center; gap: 4px; }

.pagination-wrap {
  display: flex;
  justify-content: center;
  padding: 16px;
}
</style>
