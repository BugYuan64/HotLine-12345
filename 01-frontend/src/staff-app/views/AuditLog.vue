<template>
  <!-- 部门端系统审计（需求 2.3/20 / API 8.6）：与工单审核同一 StaffLayout 画风 -->
  <StaffLayout title="系统审计">
    <div class="page-body">
      <AuditLogPanel :fetch="getMyLogs" :show-operator-filter="false" @loaded="onLoaded" @row-act="onRowAct" />
    </div>
  </StaffLayout>
</template>

<script setup>
import { onMounted, onBeforeUnmount } from 'vue'
import StaffLayout from '../components/StaffLayout.vue'
import AuditLogPanel from '../../shared/AuditLogPanel.vue'
import { getMyLogs } from '../api/log'
import { markModuleRead, getLastViewed } from '../api/unread'
import { useNewMarker } from '../../shared/useNewMarker'

// 系统审计"新"角标：与其他列表同一套工具函数（前端会话态 + 快照判新）
const newMarker = useNewMarker('audit_sys', {
  getLastViewed: (m) => getLastViewed(m),
  markRead: (m) => markModuleRead(m)
}, ['create_time'])

// AuditLogPanel load 完成：快照判新整页行 → 游标推进（徽章清零）
// 需先等待快照就绪：否则 reportRead 推进游标后 init 才取到快照，会把推进后的时间当基准导致新点全灭
const onLoaded = async (rows) => {
  await newMarker.ensureReady()
  newMarker.decorate(rows)
  newMarker.reportRead()
}
const onRowAct = (row) => { if (newMarker) newMarker.markRowSeen(row) }

onMounted(() => {
  newMarker.init()
})
onBeforeUnmount(() => { newMarker.reportRead() })
</script>

<style scoped>
.page-body { display: flex; flex-direction: column; gap: 16px; }
</style>
