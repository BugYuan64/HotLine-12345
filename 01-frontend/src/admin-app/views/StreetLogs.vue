<template>
  <!-- 街道办审计（管理端）：所有街道办端系统日志视角，可按区县下拉筛选 -->
  <AdminLayout title="街道办审计">
    <!-- 区县筛选：与下方日志筛选栏同款布局，定宽 + 按钮靠右 -->
    <el-card shadow="never" class="scope-filter-card">
      <div class="filter-bar">
        <div class="filter-item">
          <span class="filter-label">区县</span>
          <el-select v-model="county" clearable filterable placeholder="全部街道办" style="width: 190px" @change="load(1)">
            <el-option v-for="d in countyOptions" :key="d" :label="d" :value="d" />
          </el-select>
        </div>
        <div class="filter-actions">
          <el-button type="primary" @click="load(1)">查询</el-button>
          <el-button @click="county = ''; load(1)">重置</el-button>
        </div>
      </div>
    </el-card>
    <!-- :key 随区县变化强制重建面板，触发重新加载 -->
    <AuditLogPanel :key="county" ref="panelRef" :fetch="fetchLogs" :show-operator-filter="false" @loaded="onLoaded" @row-act="onRowAct" />
  </AdminLayout>
</template>

<script setup>
import { ref, onMounted, onBeforeUnmount } from 'vue'
import AdminLayout from '../components/AdminLayout.vue'
import AuditLogPanel from '../../shared/AuditLogPanel.vue'
import { getStreetLogs } from '../api/log'
import { markModuleRead, getLastViewed } from '../api/unread'
import { useNewMarker } from '../../shared/useNewMarker'

// 街道办审计"新"角标：与其他列表同一套工具函数（前端会话态 + 快照判新）
const newMarker = useNewMarker('audit_street', {
  getLastViewed: (m) => getLastViewed(m),
  markRead: (m) => markModuleRead(m)
}, ['create_time'])

const county = ref('')
const panelRef = ref()

const fetchLogs = (params) => getStreetLogs({ ...params, county: county.value || undefined })

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

// 区县下拉选项（七大区县）
const countyOptions = ['海港区', '山海关区', '北戴河区', '抚宁区', '青龙满族自治县', '昌黎县', '卢龙县']

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
