<template>
  <!-- 部门审计（管理端）：所有部门端系统日志视角，可按部门下拉筛选 -->
  <AdminLayout title="部门审计">
    <!-- 部门筛选：与下方日志筛选栏同款布局，定宽 + 按钮靠右 -->
    <el-card shadow="never" class="scope-filter-card">
      <div class="filter-bar">
        <div class="filter-item">
          <span class="filter-label">部门</span>
          <el-select v-model="dept" clearable filterable placeholder="全部部门" style="width: 190px" @change="load(1)">
            <el-option v-for="d in deptOptions" :key="d.value" :label="d.label" :value="d.value" />
          </el-select>
        </div>
        <div class="filter-actions">
          <el-button type="primary" @click="load(1)">查询</el-button>
          <el-button @click="dept = ''; load(1)">重置</el-button>
        </div>
      </div>
    </el-card>
    <!-- :key 随部门变化强制重建面板，触发重新加载 -->
    <AuditLogPanel :key="dept" ref="panelRef" :fetch="fetchLogs" :show-operator-filter="false" @loaded="onLoaded" @row-act="onRowAct" />
  </AdminLayout>
</template>

<script setup>
import { ref, onMounted, onBeforeUnmount } from 'vue'
import AdminLayout from '../components/AdminLayout.vue'
import AuditLogPanel from '../../shared/AuditLogPanel.vue'
import { getDeptLogs } from '../api/log'
import { markModuleRead, getLastViewed } from '../api/unread'
import { useNewMarker } from '../../shared/useNewMarker'

// 部门审计"新"角标：与其他列表同一套工具函数（前端会话态 + 快照判新）
const newMarker = useNewMarker('audit_dept', {
  getLastViewed: (m) => getLastViewed(m),
  markRead: (m) => markModuleRead(m)
}, ['create_time'])

const dept = ref('')
const countyCode = ref('')
const counties = ref([])
const panelRef = ref()

const fetchLogs = (params) => getDeptLogs({ ...params, dept: dept.value || undefined, county_code: countyCode.value || undefined })

// AuditLogPanel load 完成：快照判新整页行 → 游标推进（徽章清零）
// 需先等待快照就绪：否则 reportRead 推进游标后 init 才取到快照，会把推进后的时间当基准导致新点全灭
const onLoaded = async (rows) => {
  await newMarker.ensureReady()
  newMarker.decorate(rows)
  newMarker.reportRead()
}
const onRowAct = (row) => { if (newMarker) newMarker.markRowSeen(row) }

// 面板内部 load 不对外暴露时，通过 :key 重建已自动加载；此处 load 供查询按钮兜底
const load = (p = 1) => {
  panelRef.value?.load?.(p)
}

// 部门下拉选项（8 大分派部门 + 物业/民生）
const deptOptions = [
  { value: 'URBAN', label: '城管部门' },
  { value: 'TRAFFIC', label: '交通部门' },
  { value: 'MARKET', label: '市场监管部门' },
  { value: 'ENVIRONMENT', label: '生态环境部门' },
  { value: 'HOUSING', label: '住建部门' },
  { value: 'LABOR', label: '人社部门' },
  { value: 'PUBLIC', label: '市政部门' },
  { value: 'HEALTH', label: '卫健部门' },
  { value: 'PROPERTY', label: '物业部门' },
  { value: 'LIVELIHOOD', label: '民生部门' }
]

onMounted(() => {
  newMarker.init()
})
onBeforeUnmount(() => { newMarker.reportRead() })
</script>

<style scoped>
.scope-filter-card { margin-bottom: 12px; }
.scope-filter-card :deep(.el-card__body) { padding: 12px 14px; }
.filter-bar { display: flex; flex-wrap: wrap; gap: 10px 18px; align-items: center; }
.filter-item { display: flex; align-items: center; gap: 8px; }
.filter-label { flex-shrink: 0; font-size: 13px; color: #606266; }
.filter-actions { margin-left: auto; display: flex; gap: 8px; }
</style>
