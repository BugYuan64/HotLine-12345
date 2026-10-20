<template>
  <!-- 局部预警（需求 P1）：本部门仅可查看归属本部门且已发送的预警记录，可标记响应（后端按 user_type 过滤，前端不可越权） -->
  <StaffLayout title="局部预警">
    <div class="page-body">
      <el-card shadow="never" class="mb-card">
          <el-form inline class="filter-form">
            <el-form-item label="二级分类">
              <el-select v-model="filters.subCategory" clearable placeholder="全部二级分类" style="width: 140px" @change="load(1)">
                <el-option v-for="s in subCategoryOptions" :key="s" :label="s" :value="s" />
              </el-select>
            </el-form-item>
            <el-form-item label="预警等级">
              <el-select v-model="filters.warningLevel" clearable placeholder="全部等级" style="width: 120px" @change="load(1)">
                <el-option label="一般" value="GENERAL" />
                <el-option label="较重" value="SEVERE" />
                <el-option label="紧急" value="URGENT" />
              </el-select>
            </el-form-item>
            <el-form-item label="辖区">
              <el-input v-model="filters.region" clearable placeholder="全部辖区" style="width: 140px" @keyup.enter="load(1)" @clear="load(1)" />
            </el-form-item>
            <el-form-item label="时间范围">
              <el-date-picker v-model="filters.timeRange" type="datetimerange" range-separator="至"
                start-placeholder="开始" end-placeholder="结束" format="YYYY-MM-DD HH:mm:ss"
                value-format="YYYY-MM-DD HH:mm:ss" style="width: 340px" @change="load(1)" />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" @click="load(1)">查询</el-button>
            </el-form-item>
          </el-form>

          <el-table :data="rows" v-loading="loading">
            <el-table-column type="expand">
              <template #default="{ row }">
                <div class="record-detail">
                  <div class="detail-block">
                    <div class="detail-label">AI 预警总结</div>
                    <div class="detail-text">{{ row.ai_summary || '-' }}</div>
                  </div>
                  <div class="detail-block">
                    <div class="detail-label">AI 应对措施建议</div>
                    <div class="detail-text">{{ row.measure || '-' }}</div>
                  </div>
                  <div class="detail-block">
                    <div class="detail-label">参考优秀处置案例</div>
                    <div class="detail-text">{{ row.ref_cases || '-' }}</div>
                  </div>
                </div>
              </template>
            </el-table-column>
            <!-- 未读"新"小红点列：统一置于行最左侧 -->
            <el-table-column width="28" align="center">
              <template #default="{ row }">
                <NewDot :visible="row.is_new" />
              </template>
            </el-table-column>
            <el-table-column prop="warning_no" label="预警编号" width="192">
              <template #default="{ row }">
                <span>{{ row.warning_no }}</span>
              </template>
            </el-table-column>
            <el-table-column label="规则" width="160" show-overflow-tooltip>
              <template #default="{ row }">{{ row.rule_name || row.rule_id }}</template>
            </el-table-column>
            <el-table-column label="预警等级" width="100">
              <template #default="{ row }">
                <el-tag :type="levelTagType(row.warning_level)" size="small">{{ levelLabel(row.warning_level) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="分类" width="150">
              <template #default="{ row }">{{ row.category ? categoryLabel(row.category) + (row.sub_category ? '-' + row.sub_category : '') : '全部' }}</template>
            </el-table-column>
            <el-table-column prop="region" label="辖区" width="120" show-overflow-tooltip />
            <el-table-column prop="related_attention" label="累计关注度" width="100" />
            <el-table-column prop="trigger_time" label="预警时间" width="170" />
            <el-table-column label="响应状态" width="100">
              <template #default="{ row }">
                <el-tag :type="row.respond_status === 'RESPONDED' ? 'success' : 'warning'" size="small">
                  {{ row.respond_status === 'RESPONDED' ? '已响应' : '未响应' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="respond_time" label="响应时间" width="170">
              <template #default="{ row }">{{ row.respond_time || '-' }}</template>
            </el-table-column>
            <el-table-column label="操作" width="240" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" size="small" :disabled="row.respond_status === 'RESPONDED'" @click="handleRespond(row)">标记已处理</el-button>
                <template v-if="row.status === 'SENT'">
                  <el-button link type="success" size="small" @click="openDispatch(row)">分发街道办</el-button>
                  <el-button link type="info" size="small" @click="openProgress(row)">分发进度</el-button>
                </template>
              </template>
            </el-table-column>
          </el-table>
          <el-pagination class="pagination" layout="total, prev, pager, next" :total="total"
            :page-size="pageSize" :current-page="page" @current-change="load" />
        </el-card>
    </div>

    <!-- 分发预警到街道办（按区县分组勾选；辖区为空时列出全市） -->
    <el-dialog v-model="dispatchVisible" title="分发预警到街道办" width="640px" top="6vh">
      <div class="dispatch-tip muted">当前预警辖区：{{ dispatchCounty || '全市（未圈定区县）' }}；勾选要接收的街道/乡镇</div>
      <el-scrollbar max-height="420px">
        <div v-for="g in dispatchGroups" :key="g.county_code" class="dispatch-group">
          <div class="dispatch-county">
            {{ g.county_name }}
            <el-checkbox :model-value="isCountyChecked(g)" :indeterminate="isCountyIndeterminate(g)"
              @change="(val) => toggleCounty(g, !!val)">全选</el-checkbox>
          </div>
          <div class="dispatch-streets">
            <el-checkbox v-for="s in g.streets" :key="s.street_code"
              :model-value="selectedStreets.includes(s.street_name)"
              :disabled="s.dispatched"
              @change="(val) => toggleStreet(s, !!val)">
              {{ s.street_name }}<span v-if="s.dispatched" class="muted">（已分发）</span><span v-else-if="!s.has_account" class="no-account">（未注册账号）</span>
            </el-checkbox>
          </div>
        </div>
      </el-scrollbar>
      <div class="selected-bar">已选 {{ selectedStreets.length }} 个街道/乡镇</div>
      <template #footer>
        <el-button @click="dispatchVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" :disabled="!selectedStreets.length" @click="handleDispatch">确定分发</el-button>
      </template>
    </el-dialog>

    <!-- 分发进度（只读） -->
    <el-dialog v-model="progressVisible" title="分发进度" width="720px">
      <el-table :data="progressRows" v-loading="progressLoading" size="small">
        <el-table-column prop="county_name" label="区县" width="130" />
        <el-table-column prop="street_office" label="街道/乡镇" min-width="140" />
        <el-table-column label="响应状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.respond_status === 'RESPONDED' ? 'success' : 'warning'" size="small">
              {{ row.respond_status === 'RESPONDED' ? '已响应' : '未响应' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="dispatch_time" label="分发时间" width="170">
          <template #default="{ row }">{{ row.dispatch_time || '-' }}</template>
        </el-table-column>
        <el-table-column prop="respond_time" label="响应时间" width="170">
          <template #default="{ row }">{{ row.respond_time || '-' }}</template>
        </el-table-column>
        <el-table-column prop="respond_user_name" label="响应人" width="110">
          <template #default="{ row }">{{ row.respond_user_name || '-' }}</template>
        </el-table-column>
      </el-table>
    </el-dialog>
  </StaffLayout>
</template>

<script setup>
// 局部预警：本部门已发送预警记录只读 + 标记响应，越权由后端 40203 兜底
import { ref, reactive, onMounted } from 'vue'
import NewDot from '../../shared/NewDot.vue'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '../stores/auth'
import StaffLayout from '../components/StaffLayout.vue'
import { getPrimaries } from '../api/classify'
import { markModuleRead } from '../api/unread'
import { getWarnings, getSubCategories, respondWarning, getDispatchStreets, dispatchWarning, getDispatchProgress } from '../api/warning'

const auth = useAuthStore()

const levelLabel = (l) => ({ GENERAL: '一般', SEVERE: '较重', URGENT: '紧急' }[l] || l)
const levelTagType = (l) => ({ GENERAL: 'info', SEVERE: 'warning', URGENT: 'danger' }[l] || 'info')

// 一级分类 code -> 名称（动态来自 classify_primary，避免硬编码旧分类）
const primaries = ref([])
const primaryMap = reactive({})
const loadClassifyDict = async () => {
  try {
    const list = await getPrimaries()
    primaries.value = (list || []).filter((p) => p.code)
    Object.keys(primaryMap).forEach((k) => delete primaryMap[k])
    primaries.value.forEach((p) => { primaryMap[p.code] = p.name })
  } catch { /* 忽略，仅影响展示 */ }
}
const categoryLabel = (c) => primaryMap[c] || c || '-'

// ---- 列表 ----
const loading = ref(false)
const rows = ref([])
const total = ref(0)
const page = ref(1)
const pageSize = 20
const filters = reactive({ subCategory: '', warningLevel: '', region: '', timeRange: null })

// 二级分类下拉数据源：按本人固定一级分类（user_type）拉取已用到的二级分类
const subCategoryOptions = ref([])
const loadSubCategories = async () => {
  subCategoryOptions.value = (await getSubCategories(auth.userType).catch(() => [])) || []
}

const load = async (p = page.value) => {
  page.value = p
  loading.value = true
  try {
    const data = await getWarnings({
      sub_category: filters.subCategory || undefined,
      warning_level: filters.warningLevel || undefined,
      region: filters.region || undefined,
      start_time: filters.timeRange?.[0] || undefined,
      end_time: filters.timeRange?.[1] || undefined,
      page: p,
      page_size: pageSize
    })
    rows.value = data.list || []
    total.value = data.total || 0
    // 列表已渲染后上报已读，刷新侧边栏红点（与四端红点逻辑统一）
    markModuleRead('warning_record').finally(() => window.dispatchEvent(new Event('unread:refresh')))
  } finally {
    loading.value = false
  }
}

const handleRespond = async (row) => {
  await respondWarning(row.id)
  ElMessage.success('已标记为已响应')
  load()
}

// ---- 分发预警到街道办（部门端） ----
const saving = ref(false)
const dispatchVisible = ref(false)
const dispatchGroups = ref([])      // [{ county_code, county_name, streets:[{street_code, street_name, has_account, dispatched}] }]
const selectedStreets = ref([])     // 已勾选街道名
const dispatchWarningId = ref(null)
const dispatchCounty = ref('')

const openDispatch = async (row) => {
  dispatchWarningId.value = row.id
  dispatchCounty.value = row.county_name || row.region || ''
  selectedStreets.value = []
  dispatchGroups.value = []
  dispatchVisible.value = true
  try {
    dispatchGroups.value = await getDispatchStreets(row.id)
  } catch {
    dispatchGroups.value = []
  }
}

const selectableStreets = (g) => (g.streets || []).filter((s) => !s.dispatched)
const isCountyChecked = (g) => selectableStreets(g).length > 0 && selectableStreets(g).every((s) => selectedStreets.value.includes(s.street_name))
const isCountyIndeterminate = (g) => selectableStreets(g).some((s) => selectedStreets.value.includes(s.street_name)) && !isCountyChecked(g)
const toggleStreet = (s, val) => {
  if (val) {
    if (!selectedStreets.value.includes(s.street_name)) selectedStreets.value.push(s.street_name)
  } else {
    selectedStreets.value = selectedStreets.value.filter((n) => n !== s.street_name)
  }
}
const toggleCounty = (g, val) => {
  const names = selectableStreets(g).map((s) => s.street_name)
  const rest = selectedStreets.value.filter((n) => !names.includes(n))
  selectedStreets.value = val ? [...rest, ...names] : rest
}

const handleDispatch = async () => {
  saving.value = true
  try {
    const res = await dispatchWarning(dispatchWarningId.value, { street_offices: selectedStreets.value })
    ElMessage.success(`已分发 ${res?.dispatched ?? selectedStreets.value.length} 个街道/乡镇`)
    dispatchVisible.value = false
    load()
  } finally {
    saving.value = false
  }
}

// ---- 分发进度（部门端只读） ----
const progressVisible = ref(false)
const progressLoading = ref(false)
const progressRows = ref([])

const openProgress = async (row) => {
  progressVisible.value = true
  progressLoading.value = true
  try {
    progressRows.value = await getDispatchProgress(row.id)
  } finally {
    progressLoading.value = false
  }
}

onMounted(() => { loadClassifyDict(); loadSubCategories(); load() })
</script>

<style scoped>
.filter-form { margin-bottom: 4px; }
.pagination { margin-top: 16px; justify-content: flex-end; }
.record-detail { padding: 8px 16px; }
.detail-block { margin-bottom: 8px; }
.detail-label { color: var(--el-text-color-primary); font-weight: 600; font-size: 13px; margin-bottom: 4px; }
.detail-text { color: var(--el-text-color-regular); font-size: 13px; white-space: pre-line; }
.dispatch-tip { margin-bottom: 10px; font-size: 13px; }
.dispatch-group { padding: 8px 0; border-bottom: 1px dashed #ebeef5; }
.dispatch-group:last-child { border-bottom: none; }
.dispatch-county { font-weight: 600; font-size: 13px; margin-bottom: 6px; display: flex; align-items: center; gap: 12px; }
.dispatch-streets { display: flex; flex-wrap: wrap; gap: 4px 16px; }
.no-account { color: #e6a23c; font-size: 12px; }
.muted { color: #909399; }
.selected-bar { margin-top: 10px; font-size: 13px; color: var(--el-text-color-primary); }
</style>