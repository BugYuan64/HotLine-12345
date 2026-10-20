<template>
  <!-- 管理端父诉求（需求 2.3 / API 6.1）：父诉求查询、拆分、追溯、撤回、增删改 -->
  <AdminLayout title="父诉求">
    <el-card shadow="never">
      <el-form inline class="filter-form">
        <el-form-item label="诉求编号">
          <el-input v-model="filters.appealNo" clearable placeholder="诉求编号" style="width: 180px" @keyup.enter="load(1)" @clear="load(1)" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="filters.status" clearable placeholder="全部状态" style="width: 150px" @change="load(1)">
            <el-option label="待AI生成标题" value="TITLE_PENDING" />
            <el-option label="AI标题生成中" value="TITLE_GENERATING" />
            <el-option label="待拆分" value="TITLE_GENERATED" />
            <el-option label="AI拆分中" value="SPLITTING" />
            <el-option label="拆分完成" value="CHILDREN_GENERATED" />
            <el-option label="已撤回" value="WITHDRAWN" />
          </el-select>
        </el-form-item>
        <el-form-item label="关键词">
          <el-input v-model="filters.keyword" clearable placeholder="标题/内容关键词" style="width: 160px" @keyup.enter="load(1)" @clear="load(1)" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="load(1)">查询</el-button>
          <el-button @click="resetFilters">重置</el-button>
          <el-switch
            v-model="autoSplit"
            @change="onAutoSplitChange"
            active-text="自动拆分"
            inactive-text="手动拆分"
            inline-prompt
            :active-action-icon="ArrowRight"
            :inactive-action-icon="ArrowLeft"
            style="margin-left: 40px; --el-switch-on-color: #c0c4cc; --el-switch-off-color: #dcdfe6"
          />
          <el-progress
            type="dashboard"
            :percentage="autoSplitPercent"
            :width="56"
            :color="'#c0c4cc'"
            style="margin-left: 12px; vertical-align: middle"
          >
            <template #default="{ percentage }">
              <span class="dashboard-value">{{ percentage }}%</span>
            </template>
          </el-progress>
          <el-button v-if="!autoSplit && !autoSplitActive" type="primary" size="small" style="margin-left: 28px" :loading="splitLoading" :disabled="!selectedIds.length || splitLoading" @click="handleSplitSelected">{{ splitLoading ? splitProgress : '拆分所选 (' + selectedIds.length + '/10)' }}</el-button>
        </el-form-item>
      </el-form>

      <AppealTable
        :rows="rows" :total="total" :page="page" :page-size="pageSize" :loading="loading"
        :expanded-keys="expandedKeys" :status-label="statusLabel" :status-type="statusType"
        :format-time="formatTime" :selectable="(row) => row.status === 'TITLE_GENERATED' && !splittingIds.has(row.id)"
        :action-width="220"
        @selection-change="handleSelectionChange" @select="handleSelect" @select-all="handleSelectAll"
        @expand-change="handleExpandChange" @page-change="load" @size-change="handleSizeChange"
        @row-act="newMarker.markRowSeen"
      >
        <template #status="{ row }">
          <!-- 父诉求：AI标题生成中/AI拆分中（loading）/ 拆分完成（success）/ 其它 -->
          <el-tag v-if="['TITLE_GENERATING','SPLITTING'].includes(row.status)" type="warning" size="small">
            <el-icon class="is-loading"><Loading /></el-icon> {{ statusLabel(row.status) }}
          </el-tag>
          <el-tag v-else-if="row.status === 'CHILDREN_GENERATED'" type="success" size="small">{{ statusLabel(row.status) }}</el-tag>
          <el-tag v-else :type="statusType(row.status)" size="small">{{ statusLabel(row.status) }}</el-tag>
        </template>
        <template #action="{ row }">
          <el-button link type="info" @click="openChildren(row)">子诉求</el-button>
          <el-button link type="primary" @click="openEdit(row)">查看</el-button>
          <el-button link type="danger" @click="handleDelete(row)">删除</el-button>
        </template>
      </AppealTable>
    </el-card>

    <!-- 新增/编辑父诉求 -->
    <el-dialog v-model="dialogVisible" title="查看诉求" width="640px" :close-on-click-modal="false" :before-close="handleDialogClose" @closed="resetForm">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
        <el-form-item label="诉求标题" prop="title">
          <el-input v-model="form.title" maxlength="200" show-word-limit placeholder="请填写诉求标题" />
        </el-form-item>
        <el-form-item label="诉求详情" prop="content">
          <el-input v-model="form.content" type="textarea" :rows="5" maxlength="2000" show-word-limit placeholder="请填写诉求详情" />
        </el-form-item>
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="市民姓名">
              <el-input v-model="form.citizen_name" maxlength="50" placeholder="选填" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="预留手机号">
              <el-input v-model="form.citizen_phone" maxlength="11" placeholder="请输入预留手机号" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="类别">
              <el-select v-model="form.category" clearable placeholder="请选择类别" style="width: 100%">
                <el-option v-for="(label, code) in APPEAL_CATEGORY" :key="code" :label="label" :value="code" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="区/县">
              <el-input v-model="form.district" maxlength="50" placeholder="选填" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="街道/乡镇">
              <el-input v-model="form.street" maxlength="50" placeholder="选填" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="社区">
              <el-input v-model="form.community" maxlength="50" placeholder="选填" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="详细地址">
              <el-input v-model="form.address_detail" maxlength="200" placeholder="选填" />
            </el-form-item>
          </el-col>
        </el-row>
        <!-- 诉求附件（市民提交时上传；图片缩略图/文档图标，点击下载查看） -->
        <el-form-item label="附件">
          <AttachmentGrid v-if="attachments.length" :files="attachments" />
          <span v-else style="color: #c0c4cc">无附件</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="handleDialogClose(() => { dialogVisible = false })">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitForm">保存</el-button>
      </template>
    </el-dialog>

    <!-- 撤回拆分对话框 -->
    <el-dialog v-model="withdrawVisible" :title="'撤回拆分 - ' + (withdrawParent?.appeal_no || '')" width="700px" @opened="loadWithdrawChildren">
      <div class="muted" style="margin-bottom:12px">确认撤回后，下方子诉求将被删除，父诉求回退为「待拆分」状态，可重新拆分</div>
      <el-table :data="withdrawChildren" v-loading="withdrawLoading" max-height="400">
        <el-table-column prop="appeal_no" label="编号" width="190" />
        <el-table-column prop="title" label="标题" min-width="200">
          <template #default="{ row }">
            <el-input v-model="row.title" size="small" placeholder="可修改标题" @input="row._modified = true" />
          </template>
        </el-table-column>
        <el-table-column prop="content" label="内容" min-width="250">
          <template #default="{ row }">
            <el-input v-model="row.content" size="small" placeholder="可修改内容" @input="row._modified = true" />
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!withdrawChildren.length && !withdrawLoading" description="该父诉求暂无子诉求" :image-size="60" />
      <template #footer>
        <el-button @click="withdrawVisible = false">取消</el-button>
        <el-button type="primary" :loading="withdrawSubmitting" @click="handleWithdrawConfirm">确认撤回</el-button>
      </template>
    </el-dialog>

    <!-- 子诉求追溯 -->
    <el-dialog v-model="childrenVisible" :title="'子诉求列表 ' + (childrenParent?.appeal_no || '')" width="760px">
      <el-table :data="childrenRows" v-loading="childrenLoading">
        <el-table-column prop="appeal_no" label="诉求编号" width="190" />
        <el-table-column prop="title" label="标题" min-width="200" />
        <el-table-column label="类别" width="100">
          <template #default="{ row }">{{ appealCategoryLabel(row.category) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)" size="small">{{ statusLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="提交时间" width="170">
          <template #default="{ row }">{{ formatTime(row.create_time) }}</template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!childrenRows.length && !childrenLoading" description="该父诉求暂无子诉求" :image-size="60" />
      <template #footer>
        <el-button v-if="childrenParent?.status === 'CHILDREN_GENERATED'" type="warning" @click="childrenVisible = false; openWithdraw(childrenParent)">撤回拆分</el-button>
        <el-button @click="childrenVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </AdminLayout>
</template>

<script setup>
// 管理端父诉求：查询 + 拆分 + 追溯 + 撤回 + 增删改
import { ref, reactive, computed, onMounted, onBeforeUnmount } from 'vue'
import { formatTime } from '../../shared/format.js'
const loading = ref(false)
const rows = ref([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(20)
const filters = reactive({ appealNo: '', status: '', keyword: '' })
import { ElMessage, ElMessageBox } from 'element-plus'
import { Loading, ArrowLeft, ArrowRight } from '@element-plus/icons-vue'
import AdminLayout from '../components/AdminLayout.vue'
import AppealTable from '../../shared/AppealTable.vue'
import AttachmentGrid from '../../shared/AttachmentGrid.vue'
import { getAppeals, updateAppeal, deleteAppeal, getChildren, splitAppeals, withdrawSplit, getAppealConfig, setAppealConfig, getSplitTask, startAutoSplit, getAutoSplitStatus, cancelAutoSplit, getAppealAttachments } from '../api/appeal'
import { markModuleRead, getLastViewed } from '../api/unread'
import { useNewMarker } from '../../shared/useNewMarker'
import { appealStatusLabel as statusLabel, appealStatusColor as statusType, appealCategoryLabel, APPEAL_CATEGORY } from '../../shared/dict'

const splitLoading = ref(false)
const selectedIds = ref([])
const splittingIds = ref(new Set())
const expandedKeys = ref([])

// 记录展开的行 id，load() 后展开状态保持（不被轮询刷新打断）
const handleExpandChange = (row, expandedRows) => {
  expandedKeys.value = expandedRows.map(r => r.id)
  if (row) newMarker.markRowSeen(row) // 展开查看详情：该行小点消失
}
const splitProgress = ref('')
const MAX_PARENT_SELECT = 10
const tableRef = ref()
const autoSplit = ref(false)
const autoSplitActive = ref(false)
const autoSplitProgress = ref('')
// 仪表盘百分比 = 总进度（全部父诉求的已生成子诉求数 / 全部父诉求总数），与当前页无关：
// - 自动拆分任务进行中：取任务 done/total（任务级权威进度）
// - 否则：取全局统计（两个轻量请求 page_size=1 拿 total，翻页不重算）
const overallTotal = ref(0)
const overallDone = ref(0)
const taskPercent = ref(null)
const autoSplitPercent = computed(() => {
  if (taskPercent.value != null) return taskPercent.value
  return overallTotal.value > 0 ? Math.round(overallDone.value / overallTotal.value * 100) : 0
})
/** 刷新全局统计（父诉求总数 / 已生成子诉求数），非静默 load 时调用 */
const refreshOverallStats = async () => {
  try {
    const [all, done] = await Promise.all([
      getAppeals({ scope: 'parent', page: 1, page_size: 1 }),
      getAppeals({ scope: 'parent', status: 'CHILDREN_GENERATED', page: 1, page_size: 1 })
    ])
    overallTotal.value = all.total || 0
    overallDone.value = done.total || 0
  } catch { /* 统计失败不影响列表 */
  }
}

const SPLIT_STORAGE_KEY = 'splitTaskState'
const saveSplitState = (taskId, ids) => sessionStorage.setItem(SPLIT_STORAGE_KEY, JSON.stringify({ taskId, ids }))
const clearSplitState = () => sessionStorage.removeItem(SPLIT_STORAGE_KEY)

// 撤回拆分
const withdrawVisible = ref(false)
const withdrawLoading = ref(false)
const withdrawSubmitting = ref(false)
const withdrawParent = ref(null)
const withdrawChildren = ref([])

// 父诉求“新”角标（快照判新）：进入页面取游标快照，重复 load 不影响未操作行的角标
const newMarker = useNewMarker('appeal_parent', {
  getLastViewed: (m) => getLastViewed(m),
  markRead: (m) => markModuleRead(m)
}, ['create_time'])

const load = async (p = page.value, silent = false) => {
  page.value = p
  // 静默模式（SSE 兜底/轮询触发）：不显示 loading 遮罩，避免自动处理期间整页闪烁
  if (!silent) loading.value = true
  const startedAt = Date.now() // 快照基准：晚于它的 SSE 行内更新比本快照新
  try {
    const data = await getAppeals({
      appeal_no: filters.appealNo || undefined,
      status: filters.status || undefined,
      keyword: filters.keyword || undefined,
      scope: 'parent',
      page: p,
      page_size: pageSize.value
    })
    const prev = new Map(rows.value.map(r => [r.id, r]))
    rows.value = (data.list || []).map(n => {
      const old = prev.get(n.id)
      // 竞态保护：本次请求发起后才发生的 SSE 行内更新比快照新，保留行内状态，防止迟到响应回退
      if (old && old._sseAt && old._sseAt >= startedAt) {
        return { ...n, status: old.status, title: old.title ?? n.title, _sseAt: old._sseAt }
      }
      return n
    })
    total.value = data.total || 0
    newMarker.decorate(rows.value)      // 快照判新（覆盖后端 decorate）
    newMarker.reportRead()              // 推进游标并刷新侧边栏
    // 总进度统计：随可见加载（翻页/查询/进入页面）刷新，静默轮询不重复请求
    if (!silent) refreshOverallStats()
  } finally {
    loading.value = false
  }
}

const handleSizeChange = (size) => {
  pageSize.value = size
  load(1)
}

const resetFilters = () => {
  filters.appealNo = ''
  filters.status = ''
  filters.keyword = ''
  load(1)
}

const onAutoSplitChange = async (val) => {
  try { await setAppealConfig({ autoSplit: val }) } catch { /* ignore */ }
  if (val) {
    autoSplitActive.value = true
    autoSplitProgress.value = '启动中...'
    taskPercent.value = 0
    await startAutoSplit()
    pollAutoSplit()
  } else {
    await cancelAutoSplit()
    autoSplitActive.value = false
    autoSplitProgress.value = ''
    taskPercent.value = null
    splittingIds.value.clear()
    load()
  }
}

const pollAutoSplit = async () => {
  let lastDone = -1
  let lastInProgressKey = ''
  let round = 0
  while (autoSplitActive.value) {
    await new Promise(r => setTimeout(r, 1000))
    if (!autoSplitActive.value) return
    let task = null
    try { task = await getAutoSplitStatus() } catch { continue }
    if (!task) { autoSplitActive.value = false; taskPercent.value = null; load(page.value, true); return }
    // 任务级总进度驱动仪表盘（不受翻页影响）
    if (task.total > 0) taskPercent.value = Math.round(task.done / task.total * 100)
    // 先判断任务结束：做一次静默权威刷新拿到最终状态（SSE 已逐条实时推送，静默避免闪烁）
    if (task.finished || task.cancelled) {
      autoSplitProgress.value = task.cancelled ? '已取消' : '已完成 ' + task.done + ' 条'
      splittingIds.value.clear()
      taskPercent.value = null
      await load(page.value, true)
      refreshOverallStats()  // 任务结束：回到全局统计口径
      autoSplitActive.value = false
      return
    }
    if (!task.active) { autoSplitActive.value = false; taskPercent.value = null; load(page.value, true); return }
    // 进度文字：每次只更新这个 span（DOM 颗粒度，不整页刷新）
    autoSplitProgress.value = '已完成 ' + task.done + '，进行中 ' + (task.inProgress ? task.inProgress.length : 0)
    // 出队/进队可视化：inProgress 集合驱动「拆分中」标签，一条完成出队、一条认领进队，逐条实时变化
    splittingIds.value = new Set(task.inProgress || [])
    const inProgressKey = [...(task.inProgress || [])].sort().join(',')
    const changed = task.done !== lastDone || inProgressKey !== lastInProgressKey
    round++
    // done/inProgress 变化只更新进度文字与「拆分中」标签（状态由 SSE 逐条实时推送，无需整页刷新）；
    // 每 10 轮（10 秒）做一次静默兜底刷新，防止 SSE 事件丢失导致状态遗漏
    if (changed) {
      lastDone = task.done
      lastInProgressKey = inProgressKey
    }
    if (round % 10 === 0) {
      load(page.value, true)
    }
  }
}

const handleSelectionChange = (selection) => {
  selectedIds.value = selection.map((r) => r.id)
}

const handleSelect = (selection, row) => {
  if (selection.length > MAX_PARENT_SELECT) {
    tableRef.value.toggleRowSelection(row, false)
    ElMessage.warning('最多勾选 ' + MAX_PARENT_SELECT + ' 条')
  }
}

const handleSelectAll = (selection) => {
  if (selection.length > MAX_PARENT_SELECT) {
    tableRef.value.clearSelection()
    const eligible = rows.value.filter(r => !splittingIds.value.has(r.id) && r.status === 'TITLE_GENERATED').slice(0, MAX_PARENT_SELECT)
    eligible.forEach(r => tableRef.value.toggleRowSelection(r, true))
    ElMessage.warning('最多勾选 ' + MAX_PARENT_SELECT + ' 条，已自动取前 ' + MAX_PARENT_SELECT + ' 条')
  }
}

const handleSplitSelected = async () => {
  const pending = rows.value.filter((r) => selectedIds.value.includes(r.id) && r.status === 'TITLE_GENERATED')
  if (!pending.length) {
    ElMessage.error('请勾选「待拆分」状态的父诉求进行拆分')
    return
  }
  if (pending.length !== selectedIds.value.length) {
    ElMessage.error('仅「待拆分」状态的父诉求可拆分，请取消勾选其他状态的诉求')
    return
  }
  const ids = [...selectedIds.value]
  ids.forEach(id => splittingIds.value.add(id))
  splitLoading.value = true
  splitProgress.value = '正在拆分 0/' + ids.length
  try {
    const { taskId } = await splitAppeals(ids)
    saveSplitState(taskId, ids)
    load() // 提交后立即刷新：后端已 claim，父诉求状态从「待拆分」→「拆分中」
    await pollSplitTask(taskId)
  } catch (e) {
    ElMessage.error('提交拆分失败：' + (e.message || '未知错误'))
  } finally {
    splitLoading.value = false
    ids.forEach(id => splittingIds.value.delete(id))
    splitProgress.value = ''
    clearSplitState()
  }
}

const pollSplitTask = async (taskId) => {
  // 新流水线含 AI 审核/分类/标题（每子诉求多次 LLM 调用），放宽到 25 分钟
  const maxPolls = 750
  let polled = 0
  let lastDone = -1
  let lastResultCount = -1
  while (polled < maxPolls) {
    await new Promise((r) => setTimeout(r, 2000))
    polled++
    let task = null
    try { task = await getSplitTask(taskId) } catch (e) { continue }
    if (!task) continue
    splitProgress.value = '正在拆分 ' + task.done + '/' + task.total
    const results = task.results || []
    for (const r of results) {
      if (r.parentId != null && splittingIds.value.has(r.parentId)) {
        splittingIds.value.delete(r.parentId)
      }
    }
    // 仅在完成数或结果数变化时才刷新列表
    const changed = task.done !== lastDone || results.length !== lastResultCount
    if (changed) {
      lastDone = task.done
      lastResultCount = results.length
      load()
    }
    if (task.finished) {
      const results = task.results || []
      const successCount = results.filter(r => r.success).length
      const failCount = results.filter(r => !r.success).length
      if (failCount > 0) {
        const failures = results.filter(r => !r.success).map(r => r.appealNo + ':' + (r.error || '未知')).join('；')
        ElMessage.warning('拆分完成：成功 ' + successCount + ' 条，失败 ' + failCount + ' 条（' + failures + '）')
      } else {
        ElMessage.success('拆分完成，共处理 ' + successCount + ' 条')
      }
      selectedIds.value = []
      load()  // 非静默加载，内部已刷新总进度统计
      clearSplitState()
      return
    }
  }
  clearSplitState()
  ElMessage.warning('拆分仍在后台进行（AI 审核耗时较长），请稍后刷新查看结果')
}

const resumeSplitPoll = async (taskId) => {
  try {
    await pollSplitTask(taskId)
  } finally {
    splitLoading.value = false
    splitProgress.value = ''
    splittingIds.value.clear()
    clearSplitState()
  }
}

const openWithdraw = async (row) => {
  withdrawParent.value = row
  withdrawChildren.value = []
  withdrawVisible.value = true
}

const loadWithdrawChildren = async () => {
  if (!withdrawParent.value) return
  withdrawLoading.value = true
  try {
    withdrawChildren.value = await getChildren(withdrawParent.value.id)
  } finally {
    withdrawLoading.value = false
  }
}

const handleWithdrawConfirm = async () => {
  withdrawSubmitting.value = true
  try {
    for (const child of withdrawChildren.value) {
      if (child._modified) {
        await updateAppeal(child.id, 'child', { title: child.title, content: child.content })
      }
    }
    await withdrawSplit(withdrawParent.value.id)
    ElMessage.success('已撤回拆分，父诉求回退为待拆分')
    withdrawVisible.value = false
    load()
  } finally {
    withdrawSubmitting.value = false
  }
}

// —— 新增 / 编辑 ——
const formRef = ref()
const dialogVisible = ref(false)
const submitting = ref(false)
const editingId = ref(null)
const form = reactive({
  title: '', content: '', category: '', citizen_name: '', citizen_phone: '',
  district: '', street: '', community: '', address_detail: '', broad_flag: false
})

const rules = {
  title: [{ required: true, message: '请填写诉求标题', trigger: 'blur' }],
  content: [{ required: true, message: '请填写诉求详情', trigger: 'blur' }]
}

const resetForm = () => {
  editingId.value = null
  form.title = ''
  form.content = ''
  form.category = ''
  form.citizen_name = ''
  form.citizen_phone = ''
  form.district = ''
  form.street = ''
  form.community = ''
  form.address_detail = ''
  form.broad_flag = false
  formRef.value?.clearValidate()
}

// 关闭前确认：内容被修改时弹出是否保存（需求：查看后修改，退出时确认保存）
const editSnapshot = ref('')
const isDirty = () => JSON.stringify(form) !== editSnapshot.value
const handleDialogClose = (done) => {
  if (!isDirty()) { done(); return }
  ElMessageBox.confirm('内容已修改，是否保存？', '提示', {
    confirmButtonText: '保存', cancelButtonText: '不保存', type: 'warning'
  }).then(() => {
    submitForm()
  }).catch(() => { done() })
}

const attachments = ref([])
const openEdit = (row) => {
  newMarker.markRowSeen(row)   // 点开即视为已读：行“新”角标立即消失
  resetForm()
  editingId.value = row.id
  form.title = row.title || ''
  form.content = row.content || ''
  form.citizen_name = row.citizen_name || ''
  form.citizen_phone = row.citizen_phone || ''
  form.category = row.category || ''
  form.district = row.district || ''
  form.street = row.street || ''
  form.community = row.community || ''
  form.address_detail = row.address_detail || ''
  form.broad_flag = row.broad_flag === 1
  editSnapshot.value = JSON.stringify(form)
  // 附件列表（该诉求的市民上传附件）
  attachments.value = []
  getAppealAttachments(row.id, 'parent').then(list => { attachments.value = list || [] }).catch(() => {})
  dialogVisible.value = true
}

const submitForm = async () => {
  // 校验失败已在表单上红字提示：吞掉 reject（否则未处理拒绝报 Proxy(Object) 错误）
  try { await formRef.value.validate() } catch { return }
  submitting.value = true
  try {
    const payload = {
      title: form.title.trim(),
      content: form.content.trim(),
      category: form.category || undefined,
      citizen_name: form.citizen_name || undefined,
      citizen_phone: form.citizen_phone,
      district: form.district || undefined,
      street: form.street || undefined,
      community: form.community || undefined,
      address_detail: form.address_detail || undefined,
      broad_flag: form.broad_flag
    }
    await updateAppeal(editingId.value, 'parent', payload)
    ElMessage.success('保存成功')
    dialogVisible.value = false
    load()
  } catch (e) {
    ElMessage.error('保存失败：' + (e.message || '未知错误'))
  } finally {
    submitting.value = false
  }
}

const handleDelete = async (row) => {
  // 取消删除：吞掉 confirm 的 'cancel' 拒绝（否则报 Uncaught (in promise) cancel）
  try {
    await ElMessageBox.confirm('确定删除诉求「' + row.appeal_no + '」吗？删除后不可恢复', '删除确认', { type: 'warning' })
  } catch { return }
  try {
    await deleteAppeal(row.id, 'parent')
    ElMessage.success('删除成功')
    load()
  } catch (e) {
    ElMessage.error('删除失败：' + (e.message || '未知错误'))
  }
}

// —— 子诉求追溯 ——
const childrenVisible = ref(false)
const childrenLoading = ref(false)
const childrenParent = ref(null)
const childrenRows = ref([])

const openChildren = async (row) => {
  newMarker.markRowSeen(row)   // 点开子诉求列表同样视为已读
  childrenParent.value = row
  childrenRows.value = []
  childrenVisible.value = true
  childrenLoading.value = true
  try {
    childrenRows.value = await getChildren(row.id)
  } catch (e) {
    ElMessage.error('加载子诉求失败：' + (e.message || '未知错误'))
  } finally {
    childrenLoading.value = false
  }
}

// —— SSE 订阅：父诉求拆分状态变化被动推送 ——
let splitEs = null
const subscribeSplitStream = () => {
  const token = localStorage.getItem('admin_token') || ''
  splitEs = new EventSource('/api/v1/admin/appeals/auto/split/stream?token=' + token)
  let errored = false
  splitEs.addEventListener('status-change', (e) => {
    errored = false // 收到数据说明连接正常
    try {
      const data = JSON.parse(e.data) // { appealId, status, title? }
      const row = rows.value.find(r => r.id === data.appealId)
      if (row) {
        row.status = data.status // 只更新这一行，不整页 load
        row._sseAt = Date.now() // 标记行内实时更新时间：迟到的列表快照不得覆盖
        if (data.title) row.title = data.title // 标题生成后同步，避免列表/详情仍显示空标题
      } else {
        load(page.value, true) // 该行不在当前页（分页外）：静默兜底刷新，避免状态遗漏且不闪
      }
    } catch { /* ignore */ }
  })
  splitEs.onerror = () => {
    // 认证失效（401）时服务端直接关闭连接（CLOSED 态）：停止重连并提示重新登录
    if (splitEs.readyState === EventSource.CLOSED) {
      if (!errored) {
        errored = true
        ElMessage.warning('实时状态连接已断开（登录已失效），请重新登录后刷新页面')
      }
      return
    }
    // 网络抖动（CONNECTING 态）：提示一次，EventSource 自动重连
    if (!errored) {
      errored = true
      ElMessage.warning('实时状态连接中断，正在自动重连...')
    }
  }
}

onMounted(async () => {
  await newMarker.init()   // 先取游标快照，再加载列表（判新基准稳定）
  load()
  subscribeSplitStream()
  try {
    const cfg = await getAppealConfig()
    autoSplit.value = cfg.autoSplit
  } catch { /* ignore */ }
  if (autoSplit.value) {
    autoSplitActive.value = true
    pollAutoSplit()
  }
  const splitState = JSON.parse(sessionStorage.getItem(SPLIT_STORAGE_KEY) || 'null')
  if (splitState && splitState.ids && splitState.ids.length) {
    splitState.ids.forEach(id => splittingIds.value.add(id))
    splitLoading.value = true
    splitProgress.value = '正在拆分 0/' + splitState.ids.length
    resumeSplitPoll(splitState.taskId)
  }
})

onBeforeUnmount(() => {
  if (splitEs) splitEs.close()
  newMarker.reportRead() // 离开页面推进游标：本页小点全部覆灭，徽章清零
})
</script>

<style scoped>
.filter-form { margin-bottom: 4px; }
.pagination { margin-top: 16px; justify-content: flex-end; }
.muted { color: #94a3b8; font-size: 12px; margin-top: 8px; }
.expand-block { padding: 10px 24px 12px; background: #fafbfc; border-radius: 6px; }
.expand-row { display: flex; align-items: flex-start; gap: 12px; margin-bottom: 6px; font-size: 13px; color: #4b5563; line-height: 1.6; }
.expand-row .label { flex-shrink: 0; width: 90px; color: #94a3b8; }
.expand-row > span:last-child { flex: 1; min-width: 0; max-width: 900px; word-break: break-all; }
/* 表格单元格单行截断：内容太长不换行，点击左侧箭头展开看完整 */
:deep(.el-table .cell) { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.broad-label { margin-left: 10px; color: #94a3b8; font-size: 13px; }
.dashboard-value { font-size: 12px; font-weight: 600; color: #909399; }
</style>
