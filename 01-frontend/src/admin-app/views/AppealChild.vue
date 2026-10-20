<template>
  <!-- 管理端子诉求（需求 2.3 / API 6.1）：子诉求查询、生成工单、查看工单、增删改 -->
  <AdminLayout title="子诉求">
    <el-card shadow="never">
      <el-form inline class="filter-form">
        <el-form-item label="诉求编号">
          <el-input v-model="filters.appealNo" clearable placeholder="诉求编号" style="width: 180px" @keyup.enter="load(1)" @clear="load(1)" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="filters.status" clearable placeholder="全部状态" style="width: 150px" @change="load(1)">
            <el-option label="待AI生成标题" value="TITLE_PENDING" />
            <el-option label="AI标题生成中" value="TITLE_GENERATING" />
            <el-option label="待AI审核" value="AUDIT_PENDING" />
            <el-option label="AI审核中" value="AUDITING" />
            <el-option label="AI类别纠正中" value="CORRECTING" />
            <el-option label="AI一级分类" value="CLASSIFYING" />
            <el-option label="AI二级分类" value="CLASSIFYING_SUB" />
            <el-option label="待生成工单" value="READY" />
            <el-option label="生成工单中" value="ORDERING" />
            <el-option label="已生成工单" value="ORDERED" />
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
            v-model="autoOrder"
            @change="onAutoOrderChange"
            active-text="自动建单"
            inactive-text="手动建单"
            inline-prompt
            :active-action-icon="ArrowRight"
            :inactive-action-icon="ArrowLeft"
            style="margin-left: 40px; --el-switch-on-color: #c0c4cc; --el-switch-off-color: #dcdfe6"
          />
          <el-progress
            type="dashboard"
            :percentage="autoOrderPercent"
            :width="56"
            :color="'#c0c4cc'"
            style="margin-left: 12px; vertical-align: middle"
          >
            <template #default="{ percentage }">
              <span class="dashboard-value">{{ percentage }}%</span>
            </template>
          </el-progress>
          <el-button v-if="!autoOrder && !autoOrderActive" type="primary" size="small" style="margin-left: 28px" :loading="orderLoading" :disabled="!selectedOrderIds.length || orderLoading" @click="handleCreateOrders">{{ orderLoading ? orderProgress : '生成工单 (' + selectedOrderIds.length + '/10)' }}</el-button>
        </el-form-item>
      </el-form>

      <AppealTable
        :rows="rows" :total="total" :page="page" :page-size="pageSize" :loading="loading"
        :expanded-keys="expandedKeys" :status-label="statusLabel" :status-type="statusType"
        :format-time="formatTime" :selectable="(row) => row.status === 'READY' && !orderCreatingIds.has(row.id)"
        :action-width="220"
        @selection-change="handleSelectionChange" @select="handleSelect" @select-all="handleSelectAll"
        @expand-change="handleExpandChange" @page-change="load" @size-change="handleSizeChange"
        @row-act="newMarker.markRowSeen"
      >
        <template #expand="{ row }">
          <div class="expand-block">
            <div class="expand-row"><span class="label">诉求内容</span><span>{{ row.content || '-' }}</span></div>
            <div class="expand-row"><span class="label">清洗日志ID</span><span>{{ row.clean_log_id || '-' }}（清洗细则见系统审计）</span></div>
          </div>
        </template>
        <template #extra-columns>
          <el-table-column prop="parent_id" label="父诉求ID" width="100" />
        </template>
        <template #status="{ row }">
          <el-tag v-if="row.status === 'ORDERING' || orderCreatingIds.has(row.id)" type="warning" size="small">
            <el-icon class="is-loading"><Loading /></el-icon> {{ statusLabel(row.status) }}
          </el-tag>
          <!-- AI 流水线各环节：实时显示（标题生成/审核/纠正/分类） -->
          <el-tag v-else-if="['TITLE_PENDING','AUDIT_PENDING'].includes(row.status)" type="info" size="small">{{ statusLabel(row.status) }}</el-tag>
          <el-tag v-else-if="['TITLE_GENERATING','AUDITING','CORRECTING','CLASSIFYING','CLASSIFYING_SUB'].includes(row.status)" type="warning" size="small">
            <el-icon class="is-loading"><Loading /></el-icon> {{ statusLabel(row.status) }}
          </el-tag>
          <el-tag v-else :type="statusType(row.status)" size="small">{{ statusLabel(row.status) }}</el-tag>
        </template>
        <template #action="{ row }">
          <el-button link type="success" @click="openChildOrder(row)">查看工单</el-button>
          <el-button link type="primary" @click="openEdit(row)">查看</el-button>
          <el-button link type="danger" @click="handleDelete(row)">删除</el-button>
        </template>
      </AppealTable>
    </el-card>

    <!-- 新增/编辑子诉求 -->
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
        <!-- 诉求附件（市民提交时上传，继承自父诉求；图片缩略图/文档图标） -->
        <el-form-item label="附件">
          <AttachmentGrid v-if="attachments.length" :files="attachments" />
          <span v-else style="color: #c0c4cc">无附件</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="handleDialogClose(() => { dialogVisible = false })">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitForm">确定</el-button>
      </template>
    </el-dialog>

    <!-- 查看工单（只读） -->
    <el-dialog v-model="orderVisible" title="查看工单" width="640px" @opened="loadOrderDetail">
      <div v-if="!orderDetail.has_order" class="muted">该子诉求尚未生成工单</div>
      <el-form v-else label-width="90px" v-loading="orderViewLoading">
        <el-form-item label="工单编号">
          <span style="margin-right:8px">{{ orderDetail.order_no }}</span>
          <el-button link type="primary" size="small" @click="copyOrderNo">复制</el-button>
        </el-form-item>
        <el-form-item label="工单状态">
          <el-tag :type="statusType(orderDetail.status)" size="small">{{ statusLabel(orderDetail.status) }}</el-tag>
        </el-form-item>
        <el-form-item label="诉求标题"><span>{{ orderDetail.title || '-' }}</span></el-form-item>
        <el-form-item label="诉求内容"><span class="pre-wrap">{{ orderDetail.content || '-' }}</span></el-form-item>
        <el-form-item label="一级分类"><span>{{ categoryLabel(orderDetail.category) }}</span></el-form-item>
        <el-form-item label="二级分类"><span>{{ orderDetail.sub_category || '-' }}</span></el-form-item>
        <el-form-item label="分派部门"><span>{{ orderDetail.department || '-' }}</span></el-form-item>
        <el-form-item label="关注度"><span>{{ orderDetail.attention_count || 1 }}</span></el-form-item>
        <!-- 诉求附件（市民提交时上传；图片缩略图/文档图标） -->
        <el-form-item label="诉求附件">
          <AttachmentGrid v-if="(orderDetail.appeal_attachments || []).length" :files="orderDetail.appeal_attachments" />
          <span v-else style="color: #c0c4cc">无</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button type="primary" @click="orderVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </AdminLayout>
</template>

<script setup>
// 管理端子诉求：查询 + 生成工单 + 查看工单 + 增删改
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
import { getAppeals, updateAppeal, deleteAppeal, createOrders, getCreateOrderTask, startAutoCreateOrders, getAutoCreateOrdersStatus, cancelAutoCreateOrders, getChildOrder, getAppealAttachments, getAppealConfig, setAppealConfig } from '../api/appeal'
import { markModuleRead, getLastViewed } from '../api/unread'
import { useNewMarker } from '../../shared/useNewMarker'
import { getPrimaries } from '../api/classify'
import { appealStatusLabel as statusLabel, appealStatusColor as statusType, appealCategoryLabel, APPEAL_CATEGORY } from '../../shared/dict'
const orderLoading = ref(false)
const orderProgress = ref('')
const orderCreatingIds = ref(new Set())
const expandedKeys = ref([])

// 记录展开的行 id，load() 后展开状态保持（不被轮询刷新打断）
const handleExpandChange = (row, expandedRows) => {
  expandedKeys.value = expandedRows.map(r => r.id)
  if (row) newMarker.markRowSeen(row) // 展开查看详情：该行小点消失
}
const selectedOrderIds = ref([])
const MAX_CHILD_SELECT = 10
const tableRef = ref()
const autoOrder = ref(false)
const autoOrderActive = ref(false)
const autoOrderProgress = ref('')
// 仪表盘百分比 = 总进度（全部子诉求的已生成工单数 / 全部子诉求总数），与当前页无关：
// - 自动建单任务进行中：取任务 done/total（任务级权威进度）
// - 否则：取全局统计（两个轻量请求 page_size=1 拿 total，翻页不重算）
const overallTotal = ref(0)
const overallDone = ref(0)
const taskPercent = ref(null)
const autoOrderPercent = computed(() => {
  if (taskPercent.value != null) return taskPercent.value
  return overallTotal.value > 0 ? Math.round(overallDone.value / overallTotal.value * 100) : 0
})
/** 刷新全局统计（子诉求总数 / 已生成工单数），非静默 load 时调用 */
const refreshOverallStats = async () => {
  try {
    const [all, done] = await Promise.all([
      getAppeals({ scope: 'child', page: 1, page_size: 1 }),
      getAppeals({ scope: 'child', status: 'ORDERED', page: 1, page_size: 1 })
    ])
    overallTotal.value = all.total || 0
    overallDone.value = done.total || 0
  } catch { /* 统计失败不影响列表 */
  }
}

const ORDER_STORAGE_KEY = 'createOrderTaskState'
const saveOrderState = (taskId, ids) => sessionStorage.setItem(ORDER_STORAGE_KEY, JSON.stringify({ taskId, ids }))
const clearOrderState = () => sessionStorage.removeItem(ORDER_STORAGE_KEY)

// 子诉求“新”角标（快照判新）：进入页面取游标快照，SSE 重载/翻页不影响未操作行的角标
const newMarker = useNewMarker('appeal_child', {
  getLastViewed: (m) => getLastViewed(m),
  markRead: (m) => markModuleRead(m)
}, ['create_time'])

const load = async (p = page.value, silent = false) => {
  page.value = p
  // 静默模式（SSE 兜底/轮询触发）：不显示 loading 遮罩，避免自动建单期间整页闪烁
  if (!silent) loading.value = true
  const startedAt = Date.now() // 快照基准：晚于它的 SSE 行内更新比本快照新
  try {
    const data = await getAppeals({
      appeal_no: filters.appealNo || undefined,
      status: filters.status || undefined,
      keyword: filters.keyword || undefined,
      scope: 'child',
      page: p,
      page_size: pageSize.value
    })
    const prev = new Map(rows.value.map(r => [r.id, r]))
    if (silent) {
      // 静默刷新 = 就地逐行合并：复用旧行对象，只有字段变化才触发该行局部更新，
      // 避免整体替换 rows 导致整个状态列反复重渲染闪烁
      const nextIds = new Set((data.list || []).map(x => x.id))
      for (let i = rows.value.length - 1; i >= 0; i--) {
        if (!nextIds.has(rows.value[i].id)) rows.value.splice(i, 1)
      }
      for (const n of data.list || []) {
        const old = prev.get(n.id)
        if (old) {
          // 竞态保护：本次请求发起后才发生的 SSE 行内更新比快照新，保留行内状态
          if (old._sseAt && old._sseAt >= startedAt) {
            Object.assign(old, n, { status: old.status, title: old.title ?? n.title,
                                    category: old.category ?? n.category,
                                    sub_category: old.sub_category ?? n.sub_category, _sseAt: old._sseAt })
          } else {
            Object.assign(old, n)
          }
        } else {
          rows.value.push({ ...n })
        }
      }
    } else {
      rows.value = (data.list || []).map(n => {
        const old = prev.get(n.id)
        if (old && old._sseAt && old._sseAt >= startedAt) {
          return { ...n, status: old.status, title: old.title ?? n.title,
                   category: old.category ?? n.category,
                   sub_category: old.sub_category ?? n.sub_category, _sseAt: old._sseAt }
        }
        return n
      })
    }
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

const onAutoOrderChange = async (val) => {
  try { await setAppealConfig({ autoOrder: val }) } catch { /* ignore */ }
  if (val) {
    autoOrderActive.value = true
    autoOrderProgress.value = '启动中...'
    taskPercent.value = 0
    await startAutoCreateOrders()
    pollAutoCreateOrders()
  } else {
    await cancelAutoCreateOrders()
    autoOrderActive.value = false
    autoOrderProgress.value = ''
    taskPercent.value = null
    orderCreatingIds.value.clear()
    load()
  }
}

const pollAutoCreateOrders = async () => {
  let lastDone = -1
  let lastInProgressKey = ''
  let round = 0
  while (autoOrderActive.value) {
    await new Promise(r => setTimeout(r, 1000))
    if (!autoOrderActive.value) return
    let task = null
    try { task = await getAutoCreateOrdersStatus() } catch { continue }
    if (!task) { autoOrderActive.value = false; taskPercent.value = null; load(page.value, true); return }
    // 任务级总进度驱动仪表盘（不受翻页影响）
    if (task.total > 0) taskPercent.value = Math.round(task.done / task.total * 100)
    // 先判断任务结束：做一次静默权威刷新拿到最终状态（SSE 已逐条实时推送，静默避免闪烁）
    if (task.finished || task.cancelled) {
      autoOrderProgress.value = task.cancelled ? '已取消' : '已完成 ' + task.done + ' 条'
      orderCreatingIds.value.clear()
      taskPercent.value = null
      await load(page.value, true)
      refreshOverallStats()  // 任务结束：回到全局统计口径
      autoOrderActive.value = false
      return
    }
    if (!task.active) { autoOrderActive.value = false; taskPercent.value = null; load(page.value, true); return }
    // 进度文字：每次只更新这个 span（DOM 颗粒度，不整页刷新）
    autoOrderProgress.value = '已完成 ' + task.done + '，进行中 ' + (task.inProgress ? task.inProgress.length : 0)
    // 出队/进队可视化：inProgress 集合驱动「生成工单中」标签，一条完成出队、一条认领进队，逐条实时变化
    orderCreatingIds.value = new Set(task.inProgress || [])
    const inProgressKey = [...(task.inProgress || [])].sort().join(',')
    const changed = task.done !== lastDone || inProgressKey !== lastInProgressKey
    round++
    // done/inProgress 变化只更新进度文字与「生成中」标签（状态由 SSE 逐条实时推送，无需整页刷新）；
    // 每 5 轮（5 秒）做一次静默兜底刷新，防止 SSE 事件丢失导致状态遗漏
    if (changed) {
      lastDone = task.done
      lastInProgressKey = inProgressKey
    }
    if (round % 5 === 0) {
      load(page.value, true)
    }
  }
}

const handleSelectionChange = (selection) => {
  selectedOrderIds.value = selection.map((r) => r.id)
}

const handleSelect = (selection, row) => {
  if (selection.length > MAX_CHILD_SELECT) {
    tableRef.value.toggleRowSelection(row, false)
    ElMessage.warning('最多勾选 ' + MAX_CHILD_SELECT + ' 条')
  }
}

const handleSelectAll = (selection) => {
  if (selection.length > MAX_CHILD_SELECT) {
    tableRef.value.clearSelection()
    const eligible = rows.value.filter(r => r.status === 'READY').slice(0, MAX_CHILD_SELECT)
    eligible.forEach(r => tableRef.value.toggleRowSelection(r, true))
    ElMessage.warning('最多勾选 ' + MAX_CHILD_SELECT + ' 条，已自动取前 ' + MAX_CHILD_SELECT + ' 条')
  }
}

const handleCreateOrders = async () => {
  if (!selectedOrderIds.value.length) return
  const ids = [...selectedOrderIds.value]
  ids.forEach(id => orderCreatingIds.value.add(id))
  orderLoading.value = true
  orderProgress.value = '正在生成 0/' + ids.length
  try {
    const { taskId } = await createOrders(ids)
    saveOrderState(taskId, ids)
    load() // 提交后立即刷新：子诉求状态从「待生成」→「生成中」
    await pollCreateOrderTask(taskId)
  } catch (e) {
    ElMessage.error('提交生成工单失败：' + (e.message || '未知错误'))
  } finally {
    orderLoading.value = false
    ids.forEach(id => orderCreatingIds.value.delete(id))
    orderProgress.value = ''
    clearOrderState()
  }
}

const pollCreateOrderTask = async (taskId) => {
  const maxPolls = 100
  let polled = 0
  let lastDone = -1
  let lastResultCount = -1
  while (polled < maxPolls) {
    await new Promise((r) => setTimeout(r, 2000))
    polled++
    let task = null
    try { task = await getCreateOrderTask(taskId) } catch (e) { continue }
    if (!task) continue
    orderProgress.value = '正在生成 ' + task.done + '/' + task.total
    const results = task.results || []
    for (const r of results) {
      if (r.childId != null && orderCreatingIds.value.has(r.childId)) {
        orderCreatingIds.value.delete(r.childId)
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
        const failures = results.filter(r => !r.success).map(r => (r.appealNo || r.childId) + ':' + (r.error || '未知')).join('；')
        ElMessage.warning('生成工单完成：成功 ' + successCount + ' 条，失败 ' + failCount + ' 条（' + failures + '）')
      } else {
        ElMessage.success('生成工单完成，共处理 ' + successCount + ' 条')
      }
      selectedOrderIds.value = []
      load()
      clearOrderState()
      return
    }
  }
  clearOrderState()
  ElMessage.warning('生成工单超时，请稍后刷新查看结果')
}

const resumeOrderPoll = async (taskId) => {
  try {
    await pollCreateOrderTask(taskId)
  } finally {
    orderLoading.value = false
    orderProgress.value = ''
    orderCreatingIds.value.clear()
    clearOrderState()
  }
}

// —— 新增 / 编辑 ——
const formRef = ref()
const dialogVisible = ref(false)
const submitting = ref(false)
const editingId = ref(null)
const form = reactive({
  title: '', content: '', category: '', parent_id: '', citizen_name: '', citizen_phone: '',
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
  form.parent_id = ''
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
  // 附件列表（继承父诉求的市民上传附件）
  attachments.value = []
  getAppealAttachments(row.id, 'child').then(list => { attachments.value = list || [] }).catch(() => {})
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
    await updateAppeal(editingId.value, 'child', payload)
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
    await deleteAppeal(row.id, 'child')
    ElMessage.success('删除成功')
    load()
  } catch (e) {
    ElMessage.error('删除失败：' + (e.message || '未知错误'))
  }
}

// —— 查看工单 ——
const orderVisible = ref(false)
const orderViewLoading = ref(false)
const orderCurrentChildId = ref(null)
const orderDetail = reactive({ has_order: false, id: null, order_no: '', title: '', content: '', category: '', sub_category: '', department: '', status: '', attention_count: 0 })
const primaries = ref([])

const primaryMap = computed(() => Object.fromEntries(primaries.value.map(p => [p.code, p.name])))
const categoryLabel = (c) => primaryMap.value[c] || c || '-'

const loadPrimariesData = async () => {
  try { primaries.value = await getPrimaries() } catch { /* ignore */ }
}

const loadOrderDetail = async () => {
  if (!orderCurrentChildId.value) return
  orderViewLoading.value = true
  Object.assign(orderDetail, { has_order: false, id: null, order_no: '', title: '', content: '', category: '', sub_category: '', department: '', status: '', attention_count: 0 })
  try {
    const d = await getChildOrder(orderCurrentChildId.value)
    Object.assign(orderDetail, d)
  } catch (e) {
    ElMessage.error('加载工单失败：' + (e.message || '未知'))
  } finally {
    orderViewLoading.value = false
  }
}

const openChildOrder = (row) => {
  orderCurrentChildId.value = row.id
  orderVisible.value = true
}

const copyOrderNo = async () => {
  if (!orderDetail.order_no) return
  try {
    await navigator.clipboard.writeText(orderDetail.order_no)
    ElMessage.success('工单编号已复制：' + orderDetail.order_no)
  } catch (e) {
    ElMessage.error('复制失败，请手动复制')
  }
}

// —— SSE 订阅：子诉求生成工单状态变化被动推送 ——
let orderEs = null
const subscribeOrderStream = () => {
  const token = localStorage.getItem('admin_token') || ''
  orderEs = new EventSource('/api/v1/admin/appeals/auto/order/stream?token=' + token)
  let errored = false
  orderEs.addEventListener('status-change', (e) => {
    errored = false // 收到数据说明连接正常
    try {
      const data = JSON.parse(e.data) // { childId, status, title?, category? }
      const row = rows.value.find(r => r.id === data.childId)
      if (row) {
        row.status = data.status // 只更新这一行，不整页 load
        row._sseAt = Date.now() // 标记行内实时更新时间：迟到的列表快照不得覆盖
        if (data.title) row.title = data.title // 标题生成后同步，避免列表/详情仍显示空标题
        if (data.category) row.category = data.category // 类别纠正/分类后同步，列表与详情实时更新
        if (data.sub_category) row.sub_category = data.sub_category // 二级分类实时同步（一级-二级展示）
      } else {
        load(page.value, true) // 该行不在当前页（分页外）：静默兜底刷新，避免状态遗漏且不闪
      }
    } catch { /* ignore */ }
  })
  orderEs.onerror = () => {
    // 认证失效（401）时服务端直接关闭连接（CLOSED 态）：停止重连并提示重新登录
    if (orderEs.readyState === EventSource.CLOSED) {
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
  loadPrimariesData()
  subscribeOrderStream()
  try {
    const cfg = await getAppealConfig()
    autoOrder.value = cfg.autoOrder
  } catch { /* ignore */ }
  if (autoOrder.value) {
    autoOrderActive.value = true
    pollAutoCreateOrders()
  }
  const orderState = JSON.parse(sessionStorage.getItem(ORDER_STORAGE_KEY) || 'null')
  if (orderState && orderState.ids && orderState.ids.length) {
    orderState.ids.forEach(id => orderCreatingIds.value.add(id))
    orderLoading.value = true
    orderProgress.value = '正在生成 0/' + orderState.ids.length
    resumeOrderPoll(orderState.taskId)
  }
})

onBeforeUnmount(() => {
  if (orderEs) orderEs.close()
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
.pre-wrap { white-space: pre-wrap; word-break: break-all; }
</style>
