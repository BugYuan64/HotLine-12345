<template>
  <el-dialog :model-value="modelValue" :title="'工单详情 ' + (detail?.order_no || '')" :width="width"
    @update:model-value="$emit('update:modelValue', $event)">
    <el-descriptions v-if="detail" :column="2" border size="small">
      <el-descriptions-item label="工单编号">{{ detail.order_no || '-' }}</el-descriptions-item>
      <el-descriptions-item label="工单状态">
        <el-tag :type="statusTagType(detail.status)" size="small">{{ statusLabels[detail.status] || detail.status }}</el-tag>
      </el-descriptions-item>
      <el-descriptions-item label="工单内容" :span="2">
        <slot name="content" :detail="detail">{{ detail.title || '-' }}</slot>
      </el-descriptions-item>
      <el-descriptions-item label="类别">
        <slot name="category" :detail="detail">
          {{ categoryLabel(detail.category) }}<span v-if="detail.sub_category"> / {{ detail.sub_category }}</span>
        </slot>
      </el-descriptions-item>
      <el-descriptions-item label="处理部门">
        <slot name="department" :detail="detail">{{ detail.department || '-' }}</slot>
      </el-descriptions-item>
      <el-descriptions-item label="承办街道办">
        <slot name="street-office" :detail="detail">{{ detail.street_office || '-' }}</slot>
      </el-descriptions-item>
      <el-descriptions-item label="处理截止时间">{{ detail.deadline || '-' }}</el-descriptions-item>
      <!-- 各端扩展字段：管理端=关联子诉求，staff=处置方案/附件 -->
      <slot name="extra"></slot>
    </el-descriptions>
    <!-- descriptions 之后的内容（时间线等） -->
    <slot name="after"></slot>
    <template #footer>
      <slot name="footer-actions">
        <el-button type="primary" @click="$emit('update:modelValue', false)">关闭</el-button>
      </slot>
    </template>
  </el-dialog>
</template>

<script setup>
defineProps({
  modelValue: { type: Boolean, default: false },
  detail: { type: Object, default: null },
  statusLabels: { type: Object, default: () => ({}) },
  statusTagType: { type: Function, default: () => 'info' },
  categoryLabel: { type: Function, default: (c) => c || '-' },
  width: { type: String, default: '1100px' }
})
defineEmits(['update:modelValue'])
</script>
