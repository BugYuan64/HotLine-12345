<template>
  <!-- 工单审核（需求 2.4/15 权限内工单查询 / API 9.5/9.12/11.1）：
       部门人员查看本分类全部辖区工单（后端自动过滤，前端不可越权）；街道办处置走独立应用 street-app -->
  <StaffLayout title="工单审核">
    <div class="page-body">
      <el-card shadow="never" class="mb-card">
          <el-form inline class="filter-form">
            <el-form-item label="工单编号">
              <el-input v-model="filters.orderNo" clearable placeholder="工单编号" style="width: 170px" @keyup.enter="load(1)" @clear="load(1)" />
            </el-form-item>
            <el-form-item label="关键词">
              <el-input v-model="filters.keyword" clearable placeholder="诉求关键词" style="width: 150px" @keyup.enter="load(1)" @clear="load(1)" />
            </el-form-item>
            <el-form-item label="街道">
              <el-select v-model="filters.streetOffice" clearable filterable placeholder="全部街道" style="width: 150px" @change="load(1)">
                <el-option v-for="s in streetOptions" :key="s.region_name" :label="s.region_name" :value="s.region_name" />
              </el-select>
            </el-form-item>
            <el-form-item label="流转状态">
              <el-select v-model="filters.status" clearable placeholder="全部状态" style="width: 170px" @change="load(1)">
                <el-option v-for="(label, val) in statusLabels" :key="val" :label="label" :value="val" />
              </el-select>
            </el-form-item>
            <el-form-item>
              <el-button @click="load(1)">查询</el-button>
            </el-form-item>
          </el-form>

          <OrderTable
            :rows="rows" :total="total" :page="page" :page-size="pageSize" :loading="loading"
            :status-labels="statusLabels" :status-tag-type="statusTagType" :category-label="categoryLabel"
            :action-width="260" :show-deadline="false" @page-change="load" @size-change="load"
            @expand-change="(row) => { loadTimeline(row); newMarker.markRowSeen(row) }" @row-act="newMarker.markRowSeen"
          >
            <template #expand="{ row }">
              <div class="flow-expand" v-loading="expandLoading[row.id]">
                <div class="flow-title">流转记录（当前步骤已高亮）</div>
                <el-timeline v-if="timelineMap[row.id] && timelineMap[row.id].length">
                  <el-timeline-item v-for="(t, i) in timelineMap[row.id]" :key="i"
                    :type="i === timelineMap[row.id].length - 1 ? 'primary' : undefined"
                    :hollow="i !== timelineMap[row.id].length - 1"
                    :timestamp="t.time" placement="top">
                    <div class="flow-node" :class="{ current: i === timelineMap[row.id].length - 1 }">
                      【{{ t.node }}】{{ t.role }}：{{ t.content }}
                    </div>
                  </el-timeline-item>
                </el-timeline>
                <el-empty v-else-if="!expandLoading[row.id]" description="暂无流转记录" :image-size="50" />
              </div>
            </template>
            <template #extra-columns>
              <el-table-column label="辖区" min-width="110">
                <template #default="{ row }">{{ row.region || '-' }}</template>
              </el-table-column>
              <el-table-column label="承办街道办" min-width="120">
                <template #default="{ row }">{{ row.street_office || '-' }}</template>
              </el-table-column>
            </template>
            <template #status-extra="{ row }">
              <el-tag v-if="row.is_overdue === 1 || row.is_overdue === true" type="danger" size="small" effect="dark" class="overdue-tag">已超期</el-tag>
            </template>
            <template #action="{ row }">
              <el-button link type="info" size="small" @click="openDetail(row)">查看</el-button>
              <el-button v-if="row.status === 'PENDING_DEPT_PROCESS'" link type="danger" size="small" @click="openApplyReject(row)">申请驳回</el-button>
              <el-button v-if="['PENDING_DEPT_PROCESS', 'PENDING_DEPT_ASSIGN'].includes(row.status)" link type="warning" size="small" @click="openAssign(row)">分派</el-button>
              <template v-if="row.status === 'STREET_REJECTED'">
                <el-button link type="danger" size="small" @click="openDeptReject(row)">同意驳回</el-button>
                <el-button link type="warning" size="small" @click="openDeptReassign(row)">重新分派</el-button>
              </template>
            </template>
          </OrderTable>
        </el-card>
    </div>
  </StaffLayout>

  <!-- 工单详情抽屉（API 9.12 + 时间线 11.1.5） -->
  <OrderDetailDialog
    v-model="detailVisible"
    :detail="detail"
    :status-labels="statusLabels" :status-tag-type="statusTagType" :category-label="categoryLabel"
  >
    <template #content="{ detail: d }">{{ d.title }}</template>
    <template #extra>
      <el-descriptions-item label="诉求内容" :span="2">{{ detail.content }}</el-descriptions-item>
      <el-descriptions-item label="市民信息" :span="2">
        <template v-if="(detail.citizens || []).length">
          <div v-for="(c, i) in detail.citizens" :key="i" style="font-size:13px">
            {{ c.citizen_name || '匿名' }}（{{ c.citizen_phone }}）{{ c.appeal_no ? ' - ' + c.appeal_no : '' }}
          </div>
        </template>
        <span v-else>-</span>
      </el-descriptions-item>
      <el-descriptions-item label="诉求附件" :span="2">
        <AttachmentGrid v-if="(detail.appeal_attachments || []).length" :files="detail.appeal_attachments" />
        <span v-else>-</span>
      </el-descriptions-item>
      <el-descriptions-item label="二级标签">{{ detail.sub_category || '-' }}</el-descriptions-item>
      <el-descriptions-item label="工单等级">{{ detail.level }}</el-descriptions-item>
      <el-descriptions-item label="超期">
        <el-tag v-if="detail.is_overdue === 1 || detail.is_overdue === true" type="danger" size="small">已超期</el-tag>
        <span v-else>否</span>
      </el-descriptions-item>
      <template v-if="detail.dispose_plan">
        <el-descriptions-item label="处置方案" :span="2">{{ detail.dispose_plan }}</el-descriptions-item>
        <el-descriptions-item label="处置结果" :span="2">{{ detail.dispose_result }}</el-descriptions-item>
        <el-descriptions-item label="处置标签">{{ disposeTagLabel(detail.dispose_tag) }}</el-descriptions-item>
        <el-descriptions-item label="部门审核意见">{{ detail.dept_audit_remark || '-' }}</el-descriptions-item>
      </template>
      <el-descriptions-item label="处置凭证" :span="2">
        <AttachmentGrid v-if="(detail.attachments || []).length" :files="detail.attachments" />
        <span v-else>-</span>
      </el-descriptions-item>
      <!-- 市民评价（处置完成后市民可评价：部门收到评价或超7天未评价才可审核；已评价在此展示） -->
      <el-descriptions-item label="市民评价" :span="2">
        <template v-if="detail.evaluation">
          <div style="font-size:13px">
            <el-rate :model-value="detail.evaluation.score" disabled size="small" style="display:inline-flex;vertical-align:middle;margin-right:8px" />
            <span class="eval-label">{{ evalLabel(detail.evaluation.label) }}</span>
          </div>
          <div v-if="detail.evaluation.remark" class="eval-remark">“{{ detail.evaluation.remark }}”</div>
          <div class="muted">评价时间：{{ fmtTime(detail.evaluation.evaluate_time) }}</div>
        </template>
        <span v-else>-</span>
      </el-descriptions-item>
    </template>
    <template #street-office="{ detail: d }">
      <!-- 承办街道办：下拉框直接修改（选择后保存；只改街道办不改变流转状态） -->
      <el-select
        v-model="d.street_office"
        filterable clearable placeholder="请选择承办街道办"
        style="width: 100%"
        :loading="streetOptionsLoading"
        :disabled="d.is_archived === 1"
        @visible-change="(open) => open && loadDetailStreetOptions()"
        @change="(val) => saveStreetChange(d, val)"
      >
        <el-option v-for="opt in detailStreetOptions" :key="opt.value" :label="opt.label" :value="opt.value" />
        <el-option v-if="d.street_office && !detailStreetOptions.find((o) => o.value === d.street_office)" :label="`${d.street_office}（当前）`" :value="d.street_office" />
      </el-select>
    </template>
    <template #footer-actions>
      <template v-for="a in deptActions" :key="a.key">
        <el-button type="primary" size="small" @click="a.handler">{{ a.label }}</el-button>
      </template>
      <el-button @click="detailVisible = false">关闭</el-button>
    </template>
  </OrderDetailDialog>

  <!-- 部门同意驳回（填说明） -->
  <el-dialog v-model="deptRejectVisible" title="同意驳回" width="480px">
    <el-form label-width="90px">
      <el-form-item label="驳回说明" required>
        <el-input v-model="deptRejectRemark" type="textarea" :rows="3" placeholder="请说明同意驳回的理由，供管理端终审参考" />
      </el-form-item>
    </el-form>
    <div class="muted">同意后工单流转至管理端终审，由管理端决定归档或重新分派</div>
    <template #footer>
      <el-button @click="deptRejectVisible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="handleDeptReject">确认同意驳回</el-button>
    </template>
  </el-dialog>

  <!-- 部门端申请驳回（待部门端处理 → 待管理端分派） -->
  <el-dialog v-model="applyRejectVisible" title="申请驳回" width="480px">
    <el-form label-width="90px">
      <el-form-item label="驳回原因" required>
        <el-input v-model="applyRejectReason" type="textarea" :rows="3" placeholder="请说明申请驳回的原因，如：不属本部门职责范围、无法处理等" />
      </el-form-item>
    </el-form>
    <div class="muted">驳回后工单将回到管理端重新分派</div>
    <template #footer>
      <el-button @click="applyRejectVisible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="handleApplyReject">确认申请驳回</el-button>
    </template>
  </el-dialog>

  <!-- 部门核验（API 9.2） -->
  <el-dialog v-model="verifyVisible" title="部门筛选核验" width="460px">
    <el-form label-width="90px">
      <el-form-item label="核验结论" required>
        <el-radio-group v-model="verifyAction">
          <el-radio value="CONFIRM">确认归属</el-radio>
          <el-radio value="RETURN">退回重分</el-radio>
        </el-radio-group>
      </el-form-item>
      <el-form-item v-if="verifyAction === 'RETURN'" label="退回原因" required>
        <el-input v-model="verifyReason" type="textarea" :rows="3" placeholder="如：不属于本部门职责范围" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="verifyVisible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="handleVerify">提交</el-button>
    </template>
  </el-dialog>

  <!-- 分派至街道办（API 9.3） -->
  <el-dialog v-model="assignVisible" title="分派至街道办" width="460px">
    <el-form label-width="90px">
      <el-form-item label="承办街道办">
        <el-select
          v-model="assignStreetName" clearable filterable
          placeholder="缺省按工单行政区划自动匹配" style="width: 100%">
          <el-option v-for="opt in assignStreetOptions" :key="opt.value" :label="opt.label" :value="opt.value" />
        </el-select>
      </el-form-item>
      <el-form-item label="分派说明">
        <el-input v-model="assignRemark" type="textarea" :rows="2" maxlength="200" show-word-limit
          placeholder="选填：可填写分派要求、注意事项等" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="assignVisible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="handleAssignStreet">确认分派</el-button>
    </template>
  </el-dialog>

  <!-- 延期审批（API 11.2.6：街道办申请 → 部门审批） -->
  <el-dialog v-model="delayApprovalVisible" title="延期审批" width="720px">
    <el-table :data="delayApplies" v-loading="delayApprovalLoading" size="small">
      <el-table-column prop="order_no" label="工单编号" width="170" />
      <el-table-column prop="summary" label="工单标题" min-width="150" show-overflow-tooltip />
      <el-table-column prop="street_office" label="街道办" width="110" />
      <el-table-column prop="apply_user_name" label="申请人" width="90" />
      <el-table-column prop="delay_reason" label="延期原因" min-width="150" show-overflow-tooltip />
      <el-table-column label="预计完成时间" width="140">
        <template #default="{ row }">{{ fmtTime(row.expected_time) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="130" fixed="right">
        <template #default="{ row }">
          <el-button link type="success" size="small" @click="handleDelayApprove(row, 'APPROVE')">通过</el-button>
          <el-button link type="danger" size="small" @click="handleDelayApprove(row, 'REJECT')">驳回</el-button>
        </template>
      </el-table-column>
    </el-table>
    <div v-if="!delayApplies.length && !delayApprovalLoading" class="muted" style="text-align:center;padding:12px 0">暂无待审批的延期申请</div>
    <template #footer>
      <el-button @click="delayApprovalVisible = false">关闭</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
// 工单审核（部门端）：列表/详情/时间线 + 部门动作（确认/审核/核验/分派/驳回），越权由后端 40203 兜底；
// 街道办处置（处理/退回/延期/驳回）已独立为 street-app
import { ref, reactive, computed, onMounted, onUnmounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useAuthStore } from '../stores/auth'
import { getPrimaries } from '../api/classify'
import { getRegionChildren, getCounties } from '../api/region'
import { markModuleRead, getLastViewed } from '../api/unread'
import { useNewMarker } from '../../shared/useNewMarker'
import StaffLayout from '../components/StaffLayout.vue'
import {
  getOrders, getOrderDetail, verifyOrder, assignStreet, getStreetOptions,
  updateStreetOffice,
  getTimeline, confirmOrder, auditApprove, deptReject, deptReassign, applyReject,
  getDelayApplyList, approveDelay
} from '../api/order'
import OrderTable from '../../shared/OrderTable.vue'
import OrderDetailDialog from '../../shared/OrderDetailDialog.vue'
import AttachmentGrid from '../../shared/AttachmentGrid.vue'
import {
  ORDER_STATUS, orderStatusLabel, orderStatusColor, disposeTagLabel
} from '../../shared/dict'

const auth = useAuthStore()

// 工单"新"角标：与其他列表同一套工具函数（前端会话态 + 快照判新）
const newMarker = useNewMarker('work_order', {
  getLastViewed: (m) => getLastViewed(m),
  markRead: (m) => markModuleRead(m)
}, ['create_time'])

// 状态标签（部门端视角）
// 部门端列表只显示在办/待办工单：审核通过(DEPT_AUDITED)、同意街道办驳回(DEPT_REJECTED)、
// 申请驳回(PENDING_REASSIGN)及办结归档(CLOSED/ARCHIVED/RETURNED)后即从列表消失，故筛选项不提供
const DEPT_VISIBLE_STATUSES = [
  'PENDING_DEPT_PROCESS', 'PENDING_DEPT_ASSIGN', 'PENDING_STREET_PROCESS', 'STREET_REJECTED',
  'PENDING_EVALUATE', 'EVALUATED', 'STREET_DONE'
]
const statusLabels = computed(() =>
  Object.fromEntries(
    Object.entries(ORDER_STATUS)
      .filter(([k]) => DEPT_VISIBLE_STATUSES.includes(k))
      .map(([k]) => [k, orderStatusLabel(k, 'DEPT')])
  ))
const statusTagType = (s) => orderStatusColor(s, 'DEPT')
// 一级分类 code -> 名称（动态来自 classify_primary，避免硬编码旧分类）
const primaries = ref([])
const primaryMap = reactive({})

const loadClassifyDict = async () => {
  try {
    const list = await getPrimaries()
    primaries.value = (list || []).filter((p) => p.code)
    Object.keys(primaryMap).forEach((k) => delete primaryMap[k])
    primaries.value.forEach((p) => { primaryMap[p.code] = p.name })
  } catch { /* 忽略，仅影响展示 */ }
}

const categoryLabel = (c) => primaryMap[c] || c || '-'

// 街道下拉数据：遍历 7 区县收集全部街道（sys_region）
const streetOptions = ref([])
const loadStreetOptions = async () => {
  try {
    const counties = await getCounties()
    const childrenLists = await Promise.all(counties.map(ct => getRegionChildren(ct.region_code).catch(() => [])))
    const seen = new Set()
    streetOptions.value = childrenLists.flat()
      .filter(r => (r.region_name || '').includes('街道') || (r.region_name || '').includes('镇') || (r.region_name || '').includes('乡'))
      .filter(r => (seen.has(r.region_name) ? false : (seen.add(r.region_name), true)))
      .map(r => ({ region_name: r.region_name }))
  } catch { /* 忽略，仅影响筛选选项 */ }
}

// ---- 列表 ----
const loading = ref(false)
const rows = ref([])
const total = ref(0)
const page = ref(1)
const pageSize = 20
const filters = reactive({ orderNo: '', keyword: '', status: '', streetOffice: '' })
// 支持从工作台指标卡跳转携带状态筛选（如 /staff/orders?status=STREET_DONE，或 ?tab=audit 待审核多状态）
const route = useRoute()

// 「待审核」状态集合（工作台待审核卡跳转：待市民评价/已评价/街道办已处理）
const AUDIT_STATUSES = ['PENDING_EVALUATE', 'EVALUATED', 'STREET_DONE']

const load = async (p = page.value) => {
  page.value = p
  loading.value = true
  try {
    // tab=audit：用后端多状态集合筛选（statuses 逗号分隔），保证分页计数与单状态一致准确
    const auditTab = route.query.tab === 'audit' && !filters.status
    const data = await getOrders({
      order_no: filters.orderNo || undefined,
      keyword: filters.keyword || undefined,
      status: filters.status || undefined,
      statuses: auditTab ? AUDIT_STATUSES.join(',') : undefined,
      street_office: filters.streetOffice || undefined,
      page: p,
      page_size: pageSize
    })
    rows.value = data.list || []
    total.value = data.total || 0
    newMarker.decorate(rows.value)
    newMarker.reportRead()
  } finally {
    loading.value = false
  }
}

// ---- 详情 ----
const detailVisible = ref(false)
const detail = ref(null)
const saving = ref(false)

// 详情弹窗「承办街道办」下拉数据（懒加载：打开弹窗/展开下拉时按工单辖区加载）
const detailStreetOptions = ref([])
const streetOptionsLoading = ref(false)
const loadDetailStreetOptions = async () => {
  if (!detail.value || detailStreetOptions.value.length) return
  streetOptionsLoading.value = true
  try {
    const res = await getStreetOptions(detail.value.id)
    detailStreetOptions.value = (res && (res.data || res.content || res)) || []
  } catch { detailStreetOptions.value = [] }
  finally { streetOptionsLoading.value = false }
}

// 修改承办街道办（下拉选择即保存；只改街道办不改变流转状态）
const saveStreetChange = async (d, val) => {
  if (d.is_archived === 1) return
  saving.value = true
  try {
    await updateStreetOffice(d.id, { street_office: val || '' })
    ElMessage.success(val ? `承办街道办已更新为「${val}」` : '承办街道办已清空')
    refreshDetail()
  } catch (e) {
    ElMessage.error((e && e.message) || '承办街道办更新失败')
    refreshDetail()
  } finally {
    saving.value = false
  }
}

const openDetail = async (row) => {
  detail.value = await getOrderDetail(row.id)
  detailStreetOptions.value = [] // 换单重置候选，打开下拉时再加载
  detailVisible.value = true
}

const refreshDetail = async () => {
  if (detail.value) {
    detail.value = await getOrderDetail(detail.value.id)
  }
  load()
}

// 部门动作：分派/驳回已挪到表格行，详情底部只保留确认/审核
// 审核条件：待市民评价(PENDING_EVALUATE)/已评价(EVALUATED)/街道办已处理(STREET_DONE) 展示"审核通过"；
// 后端校验：收到市民评价或处置完成满7天方可审核，未满足时按钮点击由后端拦截提示
const deptActions = computed(() => {
  if (!detail.value) return []
  const s = detail.value.status
  const acts = []
  if (s === 'PENDING_DEPT_PROCESS') {
    acts.push({ key: 'confirm', label: '确认', handler: handleDeptConfirm })
  }
  if (s === 'PENDING_EVALUATE' || s === 'EVALUATED' || s === 'STREET_DONE') {
    acts.push({ key: 'audit', label: '审核通过', handler: handleDeptAudit })
  }
  return acts
})

// ---- 行展开流转时间线（懒加载，最后一条=当前进行步骤并高亮） ----
const timelineMap = reactive({})
const expandLoading = reactive({})
const loadTimeline = async (row) => {
  if (timelineMap[row.id] || expandLoading[row.id]) return
  expandLoading[row.id] = true
  try {
    const data = await getTimeline(row.id)
    timelineMap[row.id] = data.timeline || []
  } catch { timelineMap[row.id] = [] } finally { expandLoading[row.id] = false }
}

// ---- 部门端确认（待审核 → 待分派） ----
const handleDeptConfirm = async () => {
  try {
    await ElMessageBox.confirm('确认该工单归属并进入待分派？', '确认', { type: 'info' })
  } catch { return }
  saving.value = true
  try {
    await confirmOrder(detail.value.id)
    ElMessage.success('已确认，状态变为待分派')
    refreshDetail()
  } finally {
    saving.value = false
  }
}

// ---- 部门审核街道办已处理/待市民评价 → 待终审 ----
const handleDeptAudit = async () => {
  try {
    await ElMessageBox.confirm('确认审核通过街道办的处理结果？市民已评价或处置完成满7天未评价方可审核，通过后流转至全局终审', '审核', { type: 'info' })
  } catch { return }
  saving.value = true
  try {
    await auditApprove(detail.value.id)
    ElMessage.success('审核通过，流转至待终审')
    refreshDetail()
  } finally {
    saving.value = false
  }
}

// ---- 部门同意驳回 / 重新分派 ----
const deptRejectVisible = ref(false)
const deptRejectTarget = ref(null)
const deptRejectRemark = ref('')
const openDeptReject = (row) => {
  deptRejectTarget.value = row
  deptRejectRemark.value = ''
  deptRejectVisible.value = true
}
const handleDeptReject = async () => {
  if (!deptRejectRemark.value.trim()) { ElMessage.warning('请填写驳回说明'); return }
  const target = deptRejectTarget.value
  if (!target) return
  saving.value = true
  try {
    await deptReject(target.id, { reject_remark: deptRejectRemark.value.trim() })
    ElMessage.success('已同意驳回，工单流转至管理端终审')
    deptRejectVisible.value = false
    deptRejectTarget.value = null
    load()
  } finally {
    saving.value = false
  }
}
const openDeptReassign = async (row) => {
  try {
    await ElMessageBox.confirm('确认对该驳回工单重新分派？将回到待分派状态', '重新分派', { type: 'info' })
  } catch { return }
  saving.value = true
  try {
    await deptReassign(row.id)
    ElMessage.success('已重新分派，工单回到待分派')
    load()
  } finally {
    saving.value = false
  }
}

// ---- 部门端申请驳回（待部门端处理 → 待管理端分派） ----
const applyRejectVisible = ref(false)
const applyRejectTarget = ref(null)
const applyRejectReason = ref('')
const openApplyReject = (row) => {
  applyRejectTarget.value = row
  applyRejectReason.value = ''
  applyRejectVisible.value = true
}
const handleApplyReject = async () => {
  if (!applyRejectReason.value.trim()) { ElMessage.warning('请填写驳回原因'); return }
  const target = applyRejectTarget.value
  if (!target) return
  saving.value = true
  try {
    await applyReject(target.id, { reject_reason: applyRejectReason.value.trim() })
    ElMessage.success('已申请驳回，工单回到管理端重新分派')
    applyRejectVisible.value = false
    applyRejectTarget.value = null
    load()
  } finally {
    saving.value = false
  }
}

// ---- 部门核验（API 9.2） ----
const verifyVisible = ref(false)
const verifyAction = ref('CONFIRM')
const verifyReason = ref('')
const handleVerify = async () => {
  if (verifyAction.value === 'RETURN' && !verifyReason.value.trim()) {
    ElMessage.warning('退回原因不能为空')
    return
  }
  saving.value = true
  try {
    await verifyOrder(detail.value.id, {
      action: verifyAction.value,
      return_reason: verifyAction.value === 'RETURN' ? verifyReason.value.trim() : undefined
    })
    ElMessage.success(verifyAction.value === 'CONFIRM' ? '核验通过，可继续分派街道办' : '已退回重分')
    verifyVisible.value = false
    verifyReason.value = ''
    verifyAction.value = 'CONFIRM'
    refreshDetail()
  } finally {
    saving.value = false
  }
}

// ---- 分派街道办（API 9.3） ----
const assignVisible = ref(false)
const assignStreetName = ref('')
const assignRemark = ref('')
const assignStreetOptions = ref([])
const assignTarget = ref(null)

// 表格行点击「分派」：记录目标工单、加载候选街道办并打开分派弹窗
const openAssign = async (row) => {
  assignTarget.value = row
  assignStreetOptions.value = []
  assignStreetName.value = row.street_office || ''
  assignVisible.value = true
  try {
    const res = await getStreetOptions(row.id)
    assignStreetOptions.value = (res && (res.data || res.content || res)) || []
  } catch { /* 缺省按行政区划自动匹配 */ }
}

const handleAssignStreet = async () => {
  const target = assignTarget.value || detail.value
  if (!target) return
  saving.value = true
  try {
    await assignStreet(target.id, { street_office: assignStreetName.value || undefined, remark: assignRemark.value || undefined })
    ElMessage.success('已分派至街道办')
    assignVisible.value = false
    assignStreetName.value = ''
    assignRemark.value = ''
    assignTarget.value = null
    if (detail.value && detail.value.id === target.id) {
      refreshDetail()
    } else {
      load()
    }
  } finally {
    saving.value = false
  }
}

onMounted(async () => {
  await newMarker.init() // 先取游标快照，再加载列表（判新基准稳定）
  if (route.query.status) {
    filters.status = String(route.query.status)
  }
  // 工作台「待审批延期」卡跳转：/staff/orders?tab=delay 自动打开延期审批弹窗
  if (route.query.tab === 'delay') {
    openDelayApproval()
  }
  load(); loadClassifyDict()
})

// ---- 延期审批（API 11.2.6：街道办申请 → 部门审批） ----
const delayApprovalVisible = ref(false)
const delayApprovalLoading = ref(false)
const delayApplies = ref([])

const fmtTime = (v) => {
  if (!v) return '-'
  const s = String(v)
  return s.length >= 16 ? s.replace('T', ' ').slice(0, 16) : s
}

// 市民评价等级 → 中文文案（与市民端评价选项一致）
const EVAL_LABELS = {
  VERY_SATISFIED: '非常满意',
  SATISFIED: '满意',
  BASIC: '基本满意',
  DISSATISFIED: '不满意',
  VERY_DISSATISFIED: '非常不满意'
}
const evalLabel = (code) => EVAL_LABELS[code] || code || '-'

const openDelayApproval = async () => {
  delayApprovalVisible.value = true
  delayApprovalLoading.value = true
  try {
    delayApplies.value = await getDelayApplyList() || []
  } catch (e) {
    // 拉取失败保持空列表
  }
  delayApprovalLoading.value = false
}

const handleDelayApprove = async (row, action) => {
  const actText = action === 'APPROVE' ? '通过' : '驳回'
  try {
    await ElMessageBox.confirm(
      `确认${actText}工单 ${row.order_no || ''} 的延期申请？${action === 'APPROVE' ? '通过后将自动顺延工单截止时间' : ''}`,
      `延期${actText}确认`,
      { type: action === 'APPROVE' ? 'success' : 'warning' }
    )
  } catch { return }
  saving.value = true
  try {
    await approveDelay(row.id, { action, remark: '' })
    ElMessage.success(`已${actText}延期申请`)
    await openDelayApproval()
  } finally {
    saving.value = false
  }
}
onUnmounted(() => {
  newMarker.reportRead() // 离开页面推进游标：本页小点全部覆灭，徽章清零
})
</script>

<style scoped>
.filter-form { margin-bottom: 4px; }
.pagination { margin-top: 16px; justify-content: flex-end; }
.overdue-tag { margin-left: 4px; }
.kw-tag { margin-right: 6px; }
.attach-item { display: flex; align-items: center; gap: 8px; margin-bottom: 4px; }
.action-area { margin: 16px 0; display: flex; gap: 8px; flex-wrap: wrap; }
.flow-expand { padding: 4px 12px; }
.flow-title { font-weight: 600; margin-bottom: 8px; color: var(--el-text-color-primary); }
.flow-node { font-size: 13px; color: var(--el-text-color-regular); }
.flow-node.current { color: var(--el-color-primary); font-weight: 600; }
.muted { color: var(--el-text-color-secondary); font-size: 12px; margin-top: 6px; }
.eval-label { color: var(--el-text-color-primary); font-weight: 600; }
.eval-remark { font-size: 13px; color: var(--el-text-color-regular); margin-top: 4px; }
</style>
