<template>
  <div>
    <el-table :data="rows" :row-key="r => r.id" v-loading="loading" @row-click="handleRowClick($event)" @expand-change="(r, arr) => $emit('expand-change', r, arr)" @selection-change="(sel) => $emit('selection-change', sel)">
      <!-- 多选列（selectable 开启时显示，管理端批量导出用） -->
      <el-table-column v-if="selectable" type="selection" width="40" />
      <!-- 行展开（提供 #expand slot 时显示箭头：如管理端流转时间线） -->
      <el-table-column v-if="hasExpand" type="expand">
        <template #default="{ row }">
          <slot name="expand" :row="row"></slot>
        </template>
      </el-table-column>
      <!-- 未读"新"小红点列：统一置于行最左侧 -->
      <el-table-column width="28" align="center">
        <template #default="{ row }">
          <NewDot :visible="row.is_new" />
        </template>
      </el-table-column>
      <el-table-column prop="order_no" label="工单编号" width="172" show-overflow-tooltip>
        <template #default="{ row }">
          <span>{{ row.order_no }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="title" label="工单内容" min-width="150" show-overflow-tooltip />
      <el-table-column label="分类" width="90">
        <template #default="{ row }">{{ categoryLabel(row.category) }}</template>
      </el-table-column>
      <el-table-column prop="level" label="等级" width="80">
        <template #default="{ row }">
          <el-tag v-if="row.level === '一级'" type="danger" size="small">一级</el-tag>
          <el-tag v-else-if="row.level === '二级'" type="warning" size="small">二级</el-tag>
          <el-tag v-else size="small" type="info">三级</el-tag>
        </template>
      </el-table-column>
      <!-- 各端自定义的额外列（辖区/部门/二级分类/关注度/置信度等） -->
      <slot name="extra-columns"></slot>
      <el-table-column label="状态" width="150">
        <template #default="{ row }">
          <el-tag :type="statusTagType(row.status)" size="small">{{ statusLabels[row.status] || row.status }}</el-tag>
          <slot name="status-extra" :row="row"></slot>
        </template>
      </el-table-column>
      <el-table-column v-if="showDeadline" prop="deadline" label="截止时间" width="160">
        <template #default="{ row }">{{ row.deadline || '-' }}</template>
      </el-table-column>
      <el-table-column label="操作" :width="actionWidth" fixed="right">
        <template #default="{ row }">
          <slot name="action" :row="row"></slot>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination class="pagination" layout="total, sizes, prev, pager, next" :total="total"
      :page-size="pageSize" :current-page="page" @current-change="(p) => $emit('page-change', p)"
      @size-change="(s) => $emit('size-change', s)" />
  </div>
</template>

<script setup>
import { computed, useSlots } from 'vue'
import NewDot from './NewDot.vue'

defineProps({
  rows: { type: Array, default: () => [] },
  total: { type: Number, default: 0 },
  page: { type: Number, default: 1 },
  pageSize: { type: Number, default: 20 },
  loading: { type: Boolean, default: false },
  statusLabels: { type: Object, default: () => ({}) },
  statusTagType: { type: Function, default: () => 'info' },
  categoryLabel: { type: Function, default: (c) => c || '-' },
  userType: { type: String, default: 'ADMIN' },
  actionWidth: { type: Number, default: 230 },
  showDeadline: { type: Boolean, default: true },
  selectable: { type: Boolean, default: false }
})
const emit = defineEmits(['page-change', 'size-change', 'expand-change', 'row-act', 'selection-change'])
// 行点击：小点置灭（延后一拍防吞展开）+ 通知宿主；点展开箭头时跳过置灭
const handleRowClick = (row, column, event) => {
  const target = event && event.target
  setTimeout(() => {
    if (!(target && target.closest && target.closest('.el-table__expand-icon'))) {
      row.is_new = false
    }
  }, 0)
  emit('row-act', row)
}

// 提供 #expand slot 时才显示行展开箭头列
const slots = useSlots()
const hasExpand = computed(() => !!slots.expand)
</script>

<style scoped>
.pagination { margin-top: 16px; justify-content: flex-end; }
</style>
