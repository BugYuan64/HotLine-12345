<template>
  <AdminLayout title="系统审计">
    <AuditLogPanel :fetch="getLogs" @loaded="onLoaded" @row-act="onRowAct" />
  </AdminLayout>
</template>

<script setup>
import { onMounted, onBeforeUnmount } from 'vue'
import AdminLayout from '../components/AdminLayout.vue'
import AuditLogPanel from '../../shared/AuditLogPanel.vue'
import { getLogs } from '../api/log'
import { markModuleRead, getLastViewed } from '../api/unread'
import { useNewMarker } from '../../shared/useNewMarker'

// 系统审计"新"角标：与父/子/工单同一套工具函数（前端会话态 + 快照判新）
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
// 点击行：该行小点消失（面板内已置灭，这里同步会话记忆）
const onRowAct = (row) => { if (newMarker) newMarker.markRowSeen(row) }

onMounted(() => {
  newMarker.init()
})
onBeforeUnmount(() => { newMarker.reportRead() })
</script>
