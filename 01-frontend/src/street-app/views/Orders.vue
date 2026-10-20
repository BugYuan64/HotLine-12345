<template>
  <StreetLayout title="工单处理">
    <div class="page-body">
        <el-card shadow="never">
          <el-form inline class="filter-form">
            <el-form-item label="工单编号">
              <el-input v-model="filters.orderNo" clearable placeholder="工单编号" style="width: 170px" @keyup.enter="load(1)" @clear="load(1)" />
            </el-form-item>
            <el-form-item label="关键词">
              <el-input v-model="filters.keyword" clearable placeholder="诉求关键词" style="width: 150px" @keyup.enter="load(1)" @clear="load(1)" />
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
            :action-width="170" @page-change="load" @size-change="load"
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
              <el-table-column label="辖区" min-width="120">
                <template #default="{ row }">{{ row.region || '-' }}</template>
              </el-table-column>
            </template>
            <template #status-extra="{ row }">
              <el-tag v-if="row.is_overdue === 1 || row.is_overdue === true" type="danger" size="small" effect="dark" class="overdue-tag">已超期</el-tag>
            </template>
            <template #action="{ row }">
              <el-button link type="info" size="small" @click="openDetail(row)">查看</el-button>
              <template v-if="row.status === 'PENDING_STREET_PROCESS'">
                <el-button link type="primary" size="small" @click="openDispose(row)">处理</el-button>
                <el-button link type="danger" size="small" @click="openReject(row)">驳回</el-button>
              </template>
            </template>
          </OrderTable>
        </el-card>
    </div>
  </StreetLayout>

  <OrderDetailDialog v-model="detailVisible" :detail="detail" :status-labels="statusLabels" :status-tag-type="statusTagType" :category-label="categoryLabel">
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
      <template v-if="detail.dispose_plan">
        <el-descriptions-item label="处置方案" :span="2">{{ detail.dispose_plan }}</el-descriptions-item>
        <el-descriptions-item label="处置结果" :span="2">{{ detail.dispose_result }}</el-descriptions-item>
        <el-descriptions-item label="处置标签">{{ disposeTagLabel(detail.dispose_tag) }}</el-descriptions-item>
      </template>
      <el-descriptions-item label="处置凭证" :span="2">
        <AttachmentGrid v-if="(detail.attachments || []).length" :files="detail.attachments" />
        <span v-else>-</span>
      </el-descriptions-item>
      <el-descriptions-item label="部门审核意见">{{ detail.dept_audit_remark || '-' }}</el-descriptions-item>
    </template>
    <template #footer-actions>
      <el-button @click="detailVisible = false">关闭</el-button>
    </template>
  </OrderDetailDialog>

  <!-- 处理弹窗 -->
  <el-dialog v-model="disposeVisible" title="处理工单" width="620px">
    <el-form ref="disposeFormRef" :model="disposeForm" :rules="disposeRules" label-width="110px">
      <el-form-item label="处置方案" prop="dispose_plan">
        <el-input v-model="disposeForm.dispose_plan" type="textarea" :rows="3" maxlength="2000" show-word-limit />
      </el-form-item>
      <el-form-item label="处置结果" prop="dispose_result">
        <el-input v-model="disposeForm.dispose_result" type="textarea" :rows="3" maxlength="2000" show-word-limit />
      </el-form-item>
      <el-form-item label="处置标签" prop="dispose_tag">
        <el-select v-model="disposeForm.dispose_tag" style="width: 100%">
          <el-option label="已解决" value="RESOLVED" />
          <el-option label="部分解决" value="PART_RESOLVED" />
          <el-option label="无法解决" value="UNRESOLVED" />
          <el-option label="转其他部门" value="TRANSFERRED" />
        </el-select>
      </el-form-item>
      <el-form-item label="实际处置时间" prop="dispose_time">
        <el-date-picker v-model="disposeForm.dispose_time" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss+08:00" style="width: 100%" />
      </el-form-item>
      <el-form-item label="处置人员" prop="dispose_user_name">
        <el-input v-model="disposeForm.dispose_user_name" maxlength="50" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="disposeVisible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="handleDispose">提交</el-button>
    </template>
  </el-dialog>

  <!-- 驳回弹窗 -->
  <el-dialog v-model="rejectVisible" title="驳回工单" width="480px">
    <el-form label-width="90px">
      <el-form-item label="驳回原因" required>
        <el-select v-model="rejectForm.reason" style="width: 100%">
          <el-option v-for="o in rejectReasonOptions" :key="o.value" :label="o.label" :value="o.value" />
        </el-select>
      </el-form-item>
      <el-form-item v-if="rejectForm.reason === '其他'" label="补充说明" required>
        <el-input v-model="rejectForm.remark" type="textarea" :rows="3" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="rejectVisible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="handleReject">确认驳回</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref, reactive, computed, onMounted, onUnmounted } from 'vue'
import { ElMessage } from 'element-plus'
import { getPrimaries } from '../api/classify'
import { getOrders, getOrderDetail, disposeOrder, rejectOrder, getTimeline } from '../api/order'
import { markModuleRead, getLastViewed } from '../api/unread'
import { useNewMarker } from '../../shared/useNewMarker'

// 工单"新"角标：与其他列表同一套工具函数（前端会话态 + 快照判新）
const newMarker = useNewMarker('work_order', {
  getLastViewed: (m) => getLastViewed(m),
  markRead: (m) => markModuleRead(m)
}, ['create_time'])

import OrderTable from '../../shared/OrderTable.vue'
import OrderDetailDialog from '../../shared/OrderDetailDialog.vue'
import StreetLayout from '../../staff-app/components/StreetLayout.vue'
import AttachmentGrid from '../../shared/AttachmentGrid.vue'

import { ORDER_STATUS, orderStatusLabel, orderStatusColor } from '../../shared/dict'
// 街道办端列表只显示在办工单（待街道办处理）：处理/驳回/退回后即从列表消失，故筛选项只保留待处理
const STREET_VISIBLE_STATUSES = ['PENDING_STREET_PROCESS']
const statusLabels = Object.fromEntries(
  Object.entries(ORDER_STATUS)
    .filter(([k]) => STREET_VISIBLE_STATUSES.includes(k))
    .map(([k]) => [k, orderStatusLabel(k, 'STREET')])
)
const statusTagType = (s) => orderStatusColor(s, 'STREET')

const primaries = ref([])
const primaryMap = reactive({})
const loadClassifyDict = async () => {
  try {
    const list = await getPrimaries()
    primaries.value = (list || []).filter((p) => p.code)
    Object.keys(primaryMap).forEach((k) => delete primaryMap[k])
    primaries.value.forEach((p) => { primaryMap[p.code] = p.name })
  } catch { /* 忽略 */ }
}
const categoryLabel = (c) => primaryMap[c] || c || '-'
const disposeTagLabel = (t) => ({ RESOLVED: '已解决', PART_RESOLVED: '部分解决', UNRESOLVED: '无法解决', TRANSFERRED: '转其他部门' }[t] || t)


const loading = ref(false)
const rows = ref([])
const total = ref(0)
const page = ref(1)
const pageSize = 20
const filters = reactive({ orderNo: '', keyword: '', status: '' })
const load = async (p = page.value) => {
  page.value = p
  loading.value = true
  try {
    const data = await getOrders({
      order_no: filters.orderNo || undefined,
      keyword: filters.keyword || undefined,
      status: filters.status || undefined,
      page: p,
      page_size: pageSize
    })
    rows.value = data.list || []
    total.value = data.total || 0
    // 列表已渲染（含"新"角标）后上报工单模块已读，并刷新侧边栏红点
    newMarker.decorate(rows.value)
    newMarker.reportRead()
  } finally {
    loading.value = false
  }
}

const detailVisible = ref(false)
const detail = ref(null)
const saving = ref(false)
const openDetail = async (row) => {
  detail.value = await getOrderDetail(row.id)
  detailVisible.value = true
}

const disposeVisible = ref(false)
const disposeFormRef = ref()
const disposeTarget = ref(null)
const disposeForm = reactive({ dispose_plan: '', dispose_result: '', dispose_tag: 'RESOLVED', dispose_time: '', dispose_user_name: '' })
const disposeRules = {
  dispose_plan: [{ required: true, message: '处置方案不能为空', trigger: 'blur' }],
  dispose_result: [{ required: true, message: '处置结果不能为空', trigger: 'blur' }],
  dispose_tag: [{ required: true, message: '请选择处置标签', trigger: 'change' }],
  dispose_time: [{ required: true, message: '请选择实际处置时间', trigger: 'change' }],
  dispose_user_name: [{ required: true, message: '处置人员不能为空', trigger: 'blur' }]
}
const openDispose = (row) => {
  disposeTarget.value = row
  disposeForm.dispose_plan = ''
  disposeForm.dispose_result = ''
  disposeForm.dispose_tag = 'RESOLVED'
  disposeForm.dispose_time = ''
  disposeForm.dispose_user_name = ''
  disposeVisible.value = true
}
const handleDispose = async () => {
  // 校验失败时 validate() 会 reject：必须捕获并提示，避免 Unhandled rejection
  try {
    await disposeFormRef.value.validate()
  } catch {
    ElMessage.warning('请完整填写处置方案、处置结果、处置时间与处置人员')
    return
  }
  const target = disposeTarget.value
  if (!target) return
  saving.value = true
  try {
    await disposeOrder(target.id, { ...disposeForm })
    ElMessage.success('处置结果已提交')
    disposeVisible.value = false
    disposeTarget.value = null
    load()
  } catch (e) {
    // 后端校验失败（如字段缺失）时给出友好提示，不抛未处理异常
    ElMessage.error((e && e.message) || '提交失败，请检查填写内容')
  } finally {
    saving.value = false
  }
}

const rejectReasonOptions = [
  { value: '辖区不符', label: '辖区不符' },
  { value: '无法处理', label: '无法处理' },
  { value: '重复件', label: '重复件' },
  { value: '其他', label: '其他' }
]
const rejectVisible = ref(false)
const rejectTarget = ref(null)
const rejectForm = reactive({ reason: '辖区不符', remark: '' })
const openReject = (row) => {
  rejectTarget.value = row
  rejectForm.reason = '辖区不符'
  rejectForm.remark = ''
  rejectVisible.value = true
}
const handleReject = async () => {
  if (!rejectForm.reason) { ElMessage.warning('请选择驳回原因'); return }
  if (rejectForm.reason === '其他' && !rejectForm.remark.trim()) { ElMessage.warning('请填写补充说明'); return }
  const target = rejectTarget.value
  if (!target) return
  saving.value = true
  try {
    await rejectOrder(target.id, { reject_reason: rejectForm.reason, reject_remark: rejectForm.remark || undefined })
    ElMessage.success('已驳回，工单回到部门端处理')
    rejectVisible.value = false
    rejectTarget.value = null
    load()
  } finally {
    saving.value = false
  }
}

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

onMounted(async () => {
  await newMarker.init() // 先取游标快照，再加载列表（判新基准稳定）
  loadClassifyDict(); load()
})
onUnmounted(() => {
  newMarker.reportRead() // 离开页面推进游标：本页小点全部覆灭，徽章清零
})
</script>

<style scoped>
.page-body { display: flex; flex-direction: column; gap: 16px; }
.filter-form { margin-bottom: 4px; }
.overdue-tag { margin-left: 4px; }
.flow-expand { padding: 4px 12px; }
.flow-title { font-weight: 600; margin-bottom: 8px; color: var(--el-text-color-primary); }
.flow-node { font-size: 13px; color: var(--el-text-color-regular); }
.flow-node.current { color: var(--el-color-primary); font-weight: 600; }
</style>
