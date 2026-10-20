<template>
  <!-- 管理端账号审核（需求 4 / API 5.3~5.7）：自助注册申请审核列表、通过/驳回、批量审核 -->
  <AdminLayout title="账号审核">
    <el-card shadow="never">
      <el-form inline class="filter-form">
        <el-form-item label="状态">
          <el-select v-model="filters.status" clearable placeholder="全部状态" style="width: 140px" @change="load(1)">
            <el-option label="待审核" value="PENDING" />
            <el-option label="已通过" value="APPROVED" />
            <el-option label="已驳回" value="REJECTED" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="load(1)">查询</el-button>
          <el-button @click="resetFilters">重置</el-button>
        </el-form-item>
      </el-form>

      <div class="toolbar">
        <el-button type="success" :disabled="!selectedIds.length" @click="batchApprove">批量通过</el-button>
        <el-button type="danger" :disabled="!selectedIds.length" @click="batchReject">批量驳回</el-button>
      </div>

      <el-table :data="rows" v-loading="loading" @selection-change="onSelectionChange">
        <el-table-column type="selection" width="50" />
        <!-- 未读"新"小红点列：统一置于行最左侧 -->
        <el-table-column width="28" align="center">
          <template #default="{ row }">
            <NewDot :visible="row.is_new" />
          </template>
        </el-table-column>
        <el-table-column prop="job_no" label="工号" width="120" />
        <el-table-column prop="company" label="所属单位" min-width="160" show-overflow-tooltip />
        <el-table-column label="归属类型" width="110">
          <template #default="{ row }">{{ userTypeLabel(row.user_type) }}</template>
        </el-table-column>
        <el-table-column prop="region" label="辖区/部门" width="140" show-overflow-tooltip />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="statusTag(row.audit_status)" size="small">{{ statusLabel(row.audit_status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="apply_time" label="申请时间" width="170" />
        <el-table-column prop="audit_time" label="审核时间" width="170" />
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <template v-if="row.audit_status === 'PENDING'">
              <span class="handle-muted">未处理</span>
              <el-button link type="success" size="small" @click="handleApprove(row)">通过</el-button>
              <el-button link type="danger" size="small" @click="handleReject(row)">驳回</el-button>
            </template>
            <el-tag v-else type="info" size="small">已处理</el-tag>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination class="pagination" layout="total, prev, pager, next" :total="total"
        :page-size="pageSize" :current-page="page" @current-change="load" />
    </el-card>
  </AdminLayout>
</template>

<script setup>
import { captureContentScroll, restoreContentScroll } from '../../shared/contentScroll'
import { ref, reactive, onMounted } from 'vue'
import NewDot from '../../shared/NewDot.vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import AdminLayout from '../components/AdminLayout.vue'
import { getAudits, approveAudit, rejectAudit } from '../api/account'
import { markModuleRead } from '../api/unread'
import { auditStatusLabel as statusLabel, auditStatusColor as statusTag } from '../../shared/dict'

const userTypeLabel = (t) => ({
  STREET: '街道办', URBAN: '城管部门', TRAFFIC: '交通部门', PROPERTY: '物业部门',
  LIVELIHOOD: '民生部门', MARKET: '市场监管部门', ENVIRONMENT: '生态环境部门',
  HOUSING: '住建部门', LABOR: '人社部门', PUBLIC: '市政部门', HEALTH: '卫健部门'
}[t] || t)

const loading = ref(false)
const rows = ref([])
const total = ref(0)
const page = ref(1)
const pageSize = 20
const filters = reactive({ status: '' })
const selectedIds = ref([])

const load = async (p = page.value) => {
  page.value = p
  loading.value = true
  const y = captureContentScroll()
  try {
    const data = await getAudits({
      status: filters.status || undefined,
      page: p,
      page_size: pageSize
    })
    rows.value = data.list || []
    total.value = data.total || 0
    // 列表已渲染（含"新"角标）后上报账号审核模块已读，并刷新侧边栏红点
    markModuleRead('account_audit').finally(() => window.dispatchEvent(new Event('unread:refresh')))
  } finally {
    loading.value = false
    await restoreContentScroll(y)
  }
}

const resetFilters = () => {
  filters.status = ''
  load(1)
}

const onSelectionChange = (selection) => {
  selectedIds.value = selection.map(r => r.id)
}

const handleApprove = async (row) => {
  // 取消操作：吞掉 confirm 的 'cancel' 拒绝（否则报 Uncaught (in promise) cancel）
  try {
    await ElMessageBox.confirm(`确认通过 ${row.name} 的注册申请？`, '审核通过', { type: 'info' })
  } catch { return }
  await approveAudit(row.id)
  ElMessage.success('已通过')
  load()
}

const handleReject = async (row) => {
  let value
  try {
    ;({ value } = await ElMessageBox.prompt('请输入驳回原因', '驳回申请', { type: 'warning', inputType: 'textarea' }))
  } catch { return }
  await rejectAudit(row.id, value || undefined)
  ElMessage.success('已驳回')
  load()
}

const batchApprove = async () => {
  try {
    await ElMessageBox.confirm(`确认批量通过 ${selectedIds.value.length} 条申请？`, '批量审核', { type: 'info' })
  } catch { return }
  for (const id of selectedIds.value) {
    await approveAudit(id)
  }
  ElMessage.success('批量审核完成')
  load()
}

const batchReject = async () => {
  let value
  try {
    ;({ value } = await ElMessageBox.prompt('请输入驳回原因', '批量驳回', { type: 'warning', inputType: 'textarea' }))
  } catch { return }
  for (const id of selectedIds.value) {
    await rejectAudit(id, value || undefined)
  }
  ElMessage.success('批量驳回完成')
  load()
}

onMounted(() => load())
</script>

<style scoped>
.filter-form { margin-bottom: 4px; }
.toolbar { margin-bottom: 12px; display: flex; align-items: center; gap: 12px; }
.handle-muted { color: #909399; font-size: 12px; margin-right: 8px; }
.pagination { margin-top: 16px; justify-content: flex-end; }
</style>