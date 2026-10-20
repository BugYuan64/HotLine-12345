<template>
  <el-card shadow="never">
    <!-- 筛选栏：定宽筛选项 + 按钮组靠右，窄屏自动换行，label 统一左对齐 -->
    <div class="filter-bar">
      <div v-if="showOperatorFilter" class="filter-item">
        <span class="filter-label">操作人</span>
        <el-input v-model="filters.operator" clearable placeholder="操作人" style="width: 140px" @keyup.enter="load(1)" @clear="onFilterChange" />
      </div>
      <div class="filter-item">
        <span class="filter-label">关键词</span>
        <el-input v-model="filters.appealKeyword" clearable placeholder="操作人/操作名称/描述/诉求内容模糊搜索" style="width: 220px" @keyup.enter="load(1)" @clear="onFilterChange" />
      </div>
      <div class="filter-item">
        <span class="filter-label">操作类型</span>
        <el-select v-model="filters.module" clearable filterable placeholder="全部操作" style="width: 160px" @change="onFilterChange">
          <el-option v-for="m in moduleOptions" :key="m" :label="moduleLabel(m)" :value="m" />
        </el-select>
      </div>
      <div class="filter-item">
        <span class="filter-label">ID</span>
        <el-input v-model="filters.id" clearable placeholder="日志ID" style="width: 120px" @keyup.enter="load(1)" @clear="load(1)" />
      </div>
      <div class="filter-item">
        <span class="filter-label">时间范围</span>
        <el-date-picker v-model="filters.timeRange" type="datetimerange" range-separator="至"
          start-placeholder="开始时间" end-placeholder="结束时间" format="YYYY-MM-DD HH:mm:ss"
          value-format="YYYY-MM-DD HH:mm:ss" style="width: 360px" @change="onFilterChange" />
      </div>
      <div class="filter-actions">
        <el-button type="primary" @click="load(1)">查询</el-button>
        <el-button @click="resetFilters">重置</el-button>
      </div>
    </div>

    <el-table :data="rows" :row-key="r => r.id" v-loading="loading" @row-click="handleRowClick($event)">
      <!-- 未读"新"小红点列：统一置于行最左侧 -->
      <el-table-column width="28" align="center">
        <template #default="{ row }">
          <NewDot :visible="row.is_new" />
        </template>
      </el-table-column>
      <el-table-column prop="id" label="ID" width="80" />
      <el-table-column label="操作类型" width="150">
        <template #default="{ row }">
          <el-tag :type="logTypeTag(row.log_type)" size="small">{{ typeLabel(row) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="operator" label="操作人" width="160" />
      <el-table-column prop="summary" label="操作描述" min-width="220" show-overflow-tooltip />
      <el-table-column prop="create_time" label="操作时间" width="170" />
      <el-table-column label="详情" width="80">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="showDetail(row)">查看</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination class="pagination" layout="total, sizes, prev, pager, next" :total="total"
      :page-size="pageSize" :page-sizes="[10, 20, 50, 100]" :current-page="page" @current-change="load" @size-change="handleSizeChange" />
  </el-card>

  <el-dialog v-model="detailVisible" title="日志详情" width="640px">
    <el-descriptions :column="1" border>
      <el-descriptions-item label="操作类型">{{ logTypeLabel(detailRow?.log_type) }}</el-descriptions-item>
      <el-descriptions-item label="操作人">{{ detailRow?.operator || '-' }}</el-descriptions-item>
      <el-descriptions-item label="操作名称">{{ moduleLabel(detailRow?.module) }}</el-descriptions-item>
      <el-descriptions-item label="操作描述">{{ detailRow?.summary || '-' }}</el-descriptions-item>
      <el-descriptions-item label="操作时间">{{ detailRow?.create_time }}</el-descriptions-item>
      <el-descriptions-item v-if="detailRow?.confidence != null" label="置信度">{{ detailRow.confidence }}</el-descriptions-item>
      <el-descriptions-item v-if="detailRow?.model" label="模型">{{ detailRow.model }}</el-descriptions-item>
      <el-descriptions-item v-if="detailRow?.decision_basis" label="决策依据/清洗细则">
        <pre class="detail-text">{{ formatDecisionBasis(detailRow.decision_basis) }}</pre>
      </el-descriptions-item>
    </el-descriptions>
  </el-dialog>
</template>

<script setup>
import { ref, reactive, onMounted, nextTick } from 'vue'
import { useRoute } from 'vue-router'
import NewDot from './NewDot.vue'

const props = defineProps({
  fetch: { type: Function, required: true },
  showOperatorFilter: { type: Boolean, default: true }
})

const route = useRoute()

const logTypeLabel = (t) => ({ LOGIN: '登录', OPERATION: '操作', ERROR: '错误', AI_GENERATION: 'AI生成' }[t] || t)
const logTypeTag = (t) => ({ LOGIN: 'info', OPERATION: 'primary', ERROR: 'danger', AI_GENERATION: 'warning' }[t] || 'info')

// 操作名称映射（module → 中文名，区分不同操作）
const MODULE_LABELS = {
  'appeal-split': '拆分父诉求',
  'appeal-clean': '清洗子诉求',
  'appeal-audit': '诉求AI审核',
  'appeal-title': '标题AI总结',
  'appeal-submit': '市民提交诉求',
  'appeal': '诉求管理',
  'account-audit': '账号审核',
  'classify-category': '二级标签维护',
  'classify-primary': '一级分类维护',
  'classify-keyword': '关键词维护',
  'classify-rule': '分类规则维护',
  'classify-sample': 'RAG样本维护',
  'order-confirm': '工单确认',
  'order-receive': '收到工单',
  'order-dispatch': '派发工单',
  'order-dispose': '工单处置',
  'order-upgrade': '工单升级',
  'order-create': '工单生成',
  'order-import': '工单导入',
  'order-edit': '工单编辑',
  'order-delete': '工单删除',
  'order-classify-correct': '分类修正',
  'order-merge': '工单归并',
  'order-demerge': '工单解绑',
  'order-verify': '工单核验',
  'order-return': '工单退回',
  'order-audit': '工单审核',
  'order-final-audit': '终审确认',
  'order-reopen': '重开审批',
  'order-reject': '工单驳回',
  'order-reassign': '重新分派',
  'order-reclassify': '重新分类',
  'role-permission': '角色权限',
  'user-account': '用户账号管理',
  'warning-rule': '预警规则维护',
  'warning-respond': '预警响应',
  'warning-escalate': '预警升级',
  'warning-scan': '预警扫描',
  'warning-dispatch': '预警派发',
  'warning-case': '预警案例',
  'warning-review': '预警审核',
  'notice': '通知公告',
  'ai_generation': 'AI生成'
}
const moduleLabel = (m) => MODULE_LABELS[m] || m
const moduleOptions = Object.keys(MODULE_LABELS)

// 操作类型列显示：OPERATION 显示具体操作名称（moduleLabel），其他类型显示 log_type 映射
const typeLabel = (row) => (row.log_type === 'OPERATION' ? moduleLabel(row.module) : logTypeLabel(row.log_type))

const loading = ref(false)
const rows = ref([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(20)
const filters = reactive({ module: '', operator: '', id: '', timeRange: null, appealKeyword: '' })

const load = async (p = page.value) => {
  page.value = p
  loading.value = true
    // 滚动保持仅管理端日志页启用（部门端/街道办端不加，按需求）：仅管理端捕获滚动位置，不影响数据加载
  const isAdminRoute = route.path.startsWith('/admin')
  const scroller = isAdminRoute ? document.querySelector('main.content') : null
  const y = scroller ? scroller.scrollTop : null
  try {
    const data = await props.fetch({
      module: filters.module || undefined,
      operator: filters.operator || undefined,
      id: filters.id || undefined,
      appeal_keyword: filters.appealKeyword || undefined,
      start_time: filters.timeRange?.[0] || undefined,
      end_time: filters.timeRange?.[1] || undefined,
      page: p,
      page_size: pageSize.value
    })
    rows.value = data.list || []
    total.value = data.total || 0
    // 行数据（含 is_new"新"角标）已就绪：带回 rows 供宿主快照判新/整页置灭，并通知上报已读
    emit('loaded', rows.value)
  } finally {
    loading.value = false
    await nextTick()
    if (scroller && y != null) scroller.scrollTop = y
  }
}

// 暴露 load 供父组件（如部门审计）外部触发重查
const emit = defineEmits(['loaded', 'row-act', 'filters-change'])
// 行点击：小点置灭（延后一拍防吞展开）+ 通知宿主；点展开箭头时跳过置灭
const handleRowClick = (row, column, event) => {
  const target = event && event.target
  setTimeout(() => {
    if (!(target && target.closest && target.closest('.el-table__expand-icon'))) {
      row.is_new = false
    }
  }, 0)
  emit('row-act', row)
}
// 筛选变化 = 用户在主动浏览：当前页小点全部置灭（记入宿主 seenIds，重载不回弹）
const onFilterChange = () => {
  ;(rows.value || []).forEach(r => { r.is_new = false })
  emit('filters-change', rows.value)
  load(1)
}
defineExpose({ load })

const resetFilters = () => {
  filters.module = ''
  filters.operator = ''
  filters.id = ''
  filters.appealKeyword = ''
  filters.timeRange = null
  load(1)
}

const handleSizeChange = (size) => {
  pageSize.value = size
  load(1)
}

const detailVisible = ref(false)
const detailRow = ref(null)
const showDetail = (row) => {
  detailRow.value = row
  detailVisible.value = true
}

// 清洗细则/决策依据 JSON 格式化展示
const formatDecisionBasis = (s) => {
  if (!s) return '-'
  try {
    return JSON.stringify(JSON.parse(s), null, 2)
  } catch {
    return s
  }
}

onMounted(() => load())
</script>

<style scoped>
.filter-bar { display: flex; flex-wrap: wrap; gap: 10px 18px; align-items: center; margin-bottom: 16px; padding: 12px 14px; background: #f8f9fa; border: 1px solid #ebeef5; border-radius: 6px; }
.filter-item { display: flex; align-items: center; gap: 8px; }
.filter-label { flex-shrink: 0; font-size: 13px; color: #606266; }
.filter-actions { margin-left: auto; display: flex; gap: 8px; }
.pagination { margin-top: 16px; justify-content: flex-end; }
.detail-text { white-space: pre-line; font-size: 13px; color: var(--el-text-color-regular); margin-top: 4px; }
</style>
