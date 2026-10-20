<template>
  <!-- 区县 → 街道/镇/乡 两级联动下拉（四端共用）：
       - counties/streets 由父组件传入（端内 API 拉取，避免 shared 跨端依赖）
       - 父监听 district-change 自行拉取街道列表 -->
  <div class="region-select">
    <el-select :model-value="districtCode" :placeholder="districtPlaceholder" :disabled="districtDisabled"
      style="width: 100%" @update:model-value="onDistrict" @change="onDistrict">
      <el-option v-for="c in counties" :key="c.region_code" :label="c.region_name" :value="c.region_code" />
    </el-select>
    <el-select :model-value="streetCode" :placeholder="streetPlaceholder" :disabled="districtDisabled || !streets.length"
      style="width: 100%" @update:model-value="onStreet">
      <el-option v-for="s in streets" :key="s.region_code" :label="s.region_name" :value="s.region_name" />
    </el-select>
  </div>
</template>

<script setup>
defineProps({
  counties: { type: Array, default: () => [] },
  streets: { type: Array, default: () => [] },
  districtCode: { type: String, default: '' },
  streetCode: { type: String, default: '' },
  districtPlaceholder: { type: String, default: '请选择辖区' },
  streetPlaceholder: { type: String, default: '请先选择辖区' },
  districtDisabled: { type: Boolean, default: false }
})
const emit = defineEmits(['district-change', 'street-change'])
const onDistrict = (code) => emit('district-change', code)
const onStreet = (name) => emit('street-change', name)
</script>

<style scoped>
.region-select { display: flex; gap: 10px; width: 100%; }
</style>
