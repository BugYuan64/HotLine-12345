<template>
  <!-- 街道办局部预警（与部门端同款 StreetLayout 画风） -->
  <StreetLayout title="局部预警">
    <div class="page-body">
      <el-card shadow="never">
        <el-form inline class="filter-form">
          <el-form-item label="预警等级">
            <el-select v-model="filters.warningLevel" clearable placeholder="全部等级" style="width: 130px" @change="load(1)">
              <el-option label="一般" value="GENERAL" />
              <el-option label="较重" value="SEVERE" />
              <el-option label="紧急" value="URGENT" />
            </el-select>
          </el-form-item>
          <el-form-item label="辖区">
            <el-input v-model="filters.region" clearable placeholder="辖区" style="width: 130px" @keyup.enter="load(1)" @clear="load(1)" />
          </el-form-item>
          <el-form-item>
            <el-button @click="load(1)">查询</el-button>
          </el-form-item>
        </el-form>

        <el-table :data="rows" v-loading="loading">
          <!-- 未读"新"小红点列：统一置于行最左侧 -->
          <el-table-column width="28" align="center">
            <template #default="{ row }">
              <NewDot :visible="row.is_new" />
            </template>
          </el-table-column>
          <el-table-column prop="warning_no" label="预警编号" width="202">
            <template #default="{ row }">
              <span>{{ row.warning_no }}</span>
            </template>
          </el-table-column>
          <el-table-column label="等级" width="80">
            <template #default="{ row }">
              <el-tag :type="levelTagType(row.warning_level)" size="small">{{ levelLabel(row.warning_level) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="ai_summary" label="预警摘要" min-width="240" show-overflow-tooltip />
          <el-table-column prop="region" label="辖区" width="130" />
          <el-table-column prop="trigger_time" label="触发时间" width="170" />
          <el-table-column label="响应状态" width="100">
            <template #default="{ row }">
              <el-tag :type="row.respond_status === 'RESPONDED' ? 'success' : 'info'" size="small">
                {{ row.respond_status === 'RESPONDED' ? '已响应' : '未响应' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="100" fixed="right">
            <template #default="{ row }">
              <el-button v-if="row.respond_status !== 'RESPONDED'" link type="primary" size="small" @click="handleRespond(row)">标记响应</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination class="pagination" layout="total, prev, pager, next" :total="total"
          :page-size="pageSize" :current-page="page" @current-change="load" />
      </el-card>
    </div>
  </StreetLayout>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import NewDot from '../../shared/NewDot.vue'
import { ElMessage } from 'element-plus'
import StreetLayout from '../../staff-app/components/StreetLayout.vue'
import { getWarnings, respondWarning } from '../api/warning'
import { markModuleRead } from '../api/unread'

const levelLabel = (l) => ({ GENERAL: '一般', SEVERE: '较重', URGENT: '紧急' }[l] || l)
const levelTagType = (l) => ({ GENERAL: 'info', SEVERE: 'warning', URGENT: 'danger' }[l] || 'info')

const loading = ref(false)
const rows = ref([])
const total = ref(0)
const page = ref(1)
const pageSize = 20
const filters = reactive({ warningLevel: '', region: '' })

const load = async (p = page.value) => {
  page.value = p
  loading.value = true
  try {
    const data = await getWarnings({
      warning_level: filters.warningLevel || undefined,
      region: filters.region || undefined,
      page: p,
      page_size: pageSize
    })
    rows.value = data.list || []
    total.value = data.total || 0
    // 列表已渲染（含"新"角标）后上报预警模块已读，并刷新侧边栏红点
    markModuleRead('warning_record').finally(() => window.dispatchEvent(new Event('unread:refresh')))
  } finally {
    loading.value = false
  }
}

const handleRespond = async (row) => {
  await respondWarning(row.id)
  ElMessage.success('已标记响应')
  load()
}

onMounted(() => load())
</script>

<style scoped>
.page-body { display: flex; flex-direction: column; gap: 16px; }
.filter-form { margin-bottom: 4px; }
.pagination { margin-top: 16px; justify-content: flex-end; }
</style>
