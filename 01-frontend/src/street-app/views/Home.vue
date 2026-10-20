<template>
  <!-- 街道办工作台（与部门端同款 StreetLayout 画风） -->
  <StreetLayout title="街道办工作台">
    <div class="page-body">
      <el-row :gutter="16" v-loading="loading">
        <el-col :span="6">
          <el-card shadow="never" class="metric-card">
            <div class="metric-num">{{ summary.order_total || 0 }}</div>
            <div class="metric-label">工单总数</div>
          </el-card>
        </el-col>
        <el-col :span="6">
          <el-card shadow="never" class="metric-card">
            <div class="metric-num warn">{{ summary.pending_handle || 0 }}</div>
            <div class="metric-label">待处理</div>
          </el-card>
        </el-col>
        <el-col :span="6">
          <el-card shadow="never" class="metric-card">
            <div class="metric-num success">{{ summary.month_done || 0 }}</div>
            <div class="metric-label">本月已办结</div>
          </el-card>
        </el-col>
        <el-col :span="6">
          <el-card shadow="never" class="metric-card">
            <div class="metric-num">{{ summary.avg_score != null ? summary.avg_score : '-' }}</div>
            <div class="metric-label">平均评分</div>
          </el-card>
        </el-col>
      </el-row>

      <!-- 待我介入：需街道办推进的待办事项 -->
      <el-card shadow="never" class="todo-panel">
        <div class="todo-title">待我介入</div>
        <div class="todo-row">
          <div class="todo-card" :class="{ active: (summary.pending_handle || 0) > 0 }" @click="router.push('/street/orders')">
            <div class="todo-label">待处理工单</div>
            <div class="todo-num">{{ summary.pending_handle || 0 }}</div>
          </div>
          <div class="todo-card" :class="{ active: warnCount > 0 }" @click="router.push('/street/warnings')">
            <div class="todo-label">待响应预警</div>
            <div class="todo-num">{{ warnCount }}</div>
          </div>
        </div>
      </el-card>
    </div>
  </StreetLayout>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import StreetLayout from '../../staff-app/components/StreetLayout.vue'
import { getWorkbench } from '../api/order'
import { getUnrespondedCount } from '../api/warning'

const router = useRouter()
const loading = ref(false)
const summary = ref({})
// 待响应预警：分发给本街道且未响应
const warnCount = ref(0)

onMounted(async () => {
  loading.value = true
  try {
    summary.value = await getWorkbench()
  } catch { /* 忽略 */ }
  loading.value = false
  try {
    const w = await getUnrespondedCount()
    warnCount.value = w?.unresponded_count || 0
  } catch { /* 忽略 */ }
})
</script>

<style scoped>
.page-body { display: flex; flex-direction: column; gap: 16px; }
.metric-card { text-align: center; padding: 8px; }
.metric-num { font-size: 28px; font-weight: 700; color: #409eff; }
.metric-num.warn { color: #e6a23c; }
.metric-num.success { color: #67c23a; }
.metric-label { font-size: 13px; color: #909399; margin-top: 4px; }
.todo-panel { border-radius: var(--app-radius-lg); }
.todo-title { font-size: 15px; font-weight: 600; color: #303133; margin-bottom: 12px; }
.todo-row { display: grid; grid-template-columns: repeat(2, 1fr); gap: 12px; }
.todo-card {
  border: 1px solid #ebeef5;
  border-radius: 8px;
  padding: 12px 14px;
  cursor: pointer;
  background: #fafafa;
  transition: all 0.2s;
}
.todo-card:hover { transform: translateY(-2px); box-shadow: var(--app-shadow-md); }
.todo-card.active { background: #fdf6ec; border-color: #f3d19e; }
.todo-label { font-size: 12px; color: #909399; margin-bottom: 6px; }
.todo-num { font-size: 24px; font-weight: 700; color: #303133; line-height: 1; }
.todo-card.active .todo-num { color: #e6a23c; }
</style>
