<template>
  <div>
    <el-table ref="tableRef" :data="rows" row-key="id" :expand-row-keys="expandedKeys" v-loading="loading"
      @row-click="handleRowClick($event)"
      @selection-change="(v) => $emit('selection-change', v)"
      @select="(v) => $emit('select', v)"
      @select-all="(v) => $emit('select-all', v)"
      @expand-change="(r, arr) => $emit('expand-change', r, arr)">
      <el-table-column type="selection" width="50" :selectable="selectable" />
      <el-table-column type="expand">
        <template #default="{ row }">
          <slot name="expand" :row="row">
            <div class="expand-block">
              <div class="expand-row"><span class="label">诉求内容</span><span>{{ row.content || '-' }}</span></div>
            </div>
          </slot>
        </template>
      </el-table-column>
      <!-- 未读"新"小红点列：统一置于行最左侧 -->
      <el-table-column width="28" align="center">
        <template #default="{ row }">
          <NewDot :visible="row.is_new" />
        </template>
      </el-table-column>
      <el-table-column prop="appeal_no" label="诉求编号" width="212">
        <template #default="{ row }">
          <span>{{ row.appeal_no }}</span>
        </template>
      </el-table-column>
      <slot name="extra-columns"></slot>
      <el-table-column prop="title" label="标题" min-width="180" show-overflow-tooltip>
        <template #default="{ row }">
          <span v-if="row.title">{{ row.title }}</span>
          <span v-else style="color: #c0c4cc">AI 总结生成中…</span>
        </template>
      </el-table-column>
      <el-table-column label="一级分类" width="120">
        <template #default="{ row }">{{ categoryLabel(row.category) }}</template>
      </el-table-column>
      <el-table-column label="二级分类" width="130" show-overflow-tooltip>
        <!-- 子诉求 AI 二级分类结果；父诉求无二级字段显示 - -->
        <template #default="{ row }">
          <span v-if="row.sub_category">{{ row.sub_category }}</span>
          <span v-else style="color: #c0c4cc">-</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <slot name="status" :row="row">
            <el-tag :type="statusType(row.status)" size="small">{{ statusLabel(row.status) }}</el-tag>
          </slot>
        </template>
      </el-table-column>
      <el-table-column label="所在地区" min-width="140" show-overflow-tooltip>
        <template #default="{ row }">{{ row.broad_flag === 1 ? '秦皇岛' : [row.district, row.address_detail].filter(Boolean).join(' ') || '-' }}</template>
      </el-table-column>
      <el-table-column label="提交时间" width="170">
        <template #default="{ row }">{{ formatTime(row.create_time) }}</template>
      </el-table-column>
      <el-table-column label="操作" :width="actionWidth" fixed="right">
        <template #default="{ row }">
          <slot name="action" :row="row"></slot>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination class="pagination" layout="total, sizes, prev, pager, next" :total="total"
      :page-size="pageSize" :page-sizes="[10, 20, 50, 100]" :current-page="page"
      @current-change="(p) => $emit('page-change', p)" @size-change="(s) => $emit('size-change', s)" />
  </div>
</template>

<script setup>
import { appealCategoryLabel as categoryLabel } from './dict/appealStatus'
import NewDot from './NewDot.vue'

defineProps({
  rows: { type: Array, default: () => [] },
  total: { type: Number, default: 0 },
  page: { type: Number, default: 1 },
  pageSize: { type: Number, default: 20 },
  loading: { type: Boolean, default: false },
  expandedKeys: { type: Array, default: () => [] },
  statusLabel: { type: Function, default: (s) => s },
  statusType: { type: Function, default: () => 'info' },
  formatTime: { type: Function, default: (t) => (t ? String(t).replace('T', ' ') : '-') },
  selectable: { type: Function, default: () => true },
  actionWidth: { type: Number, default: 220 }
})
const emit = defineEmits(['selection-change', 'select', 'select-all', 'expand-change', 'page-change', 'size-change', 'row-act'])
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
</script>

<style scoped>
.pagination { margin-top: 16px; justify-content: flex-end; }
/* 默认展开内容：限宽+最多3行，悬停展开完整 */
.expand-block { padding: 10px 24px 12px; background: #fafbfc; border-radius: 6px; }
.expand-row { display: flex; align-items: flex-start; gap: 12px; margin-bottom: 6px; font-size: 13px; color: #4b5563; line-height: 1.6; }
.expand-row .label { flex-shrink: 0; width: 90px; color: #94a3b8; }
.expand-row > span:last-child { flex: 1; min-width: 0; max-width: 900px; word-break: break-all; }
</style>
