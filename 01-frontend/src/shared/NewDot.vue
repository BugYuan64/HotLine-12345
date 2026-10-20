<template>
  <!-- 统一"新"角标：Element Plus el-badge 点模式（绿色，行首提示该行有新增/状态变更） -->
  <el-badge v-if="visible" is-dot class="new-dot" />
</template>

<script setup>
defineProps({
  visible: { type: Boolean, default: false }
})
</script>

<style scoped>
.new-dot {
  display: inline-flex;
  align-items: center;
  margin-right: 6px;
  vertical-align: middle;
}
.new-dot :deep(.el-badge__content) {
  position: static;
  transform: none;
  background: #8fd1a3;
  border: none;
  box-shadow: none;
  width: 9px;
  height: 9px;
}
</style>
