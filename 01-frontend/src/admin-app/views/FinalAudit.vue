<template>
  <!-- 管理端终审归档（新流程）：归档工单只读列表，终审确认动作在「工单管理」执行 -->
  <AdminLayout title="终审归档">
    <el-card shadow="never">
      <el-form inline class="filter-form">
        <el-form-item label="工单编号">
          <el-input v-model="filters.orderNo" clearable placeholder="工单编号" style="width: 160px" @keyup.enter="load(1)" @clear="load(1)" />
        </el-form-item>
        <el-form-item label="分类">
          <el-select v-model="filters.category" clearable placeholder="全部分类" style="width: 120px" @change="load(1)">
            <el-option v-for="p in primaries" :key="p.code" :label="p.name" :value="p.code" />
          </el-select>
        </el-form-item>
        <!-- 时间段筛选：创建与完成时间均落在区间内的归档工单（未完成的按归档时间口径） -->
        <el-form-item label="时间范围">
          <el-date-picker
            v-model="timeRange"
            type="datetimerange"
            range-separator="至"
            start-placeholder="开始时间"
            end-placeholder="结束时间"
            value-format="YYYY-MM-DD HH:mm:ss"
            style="width: 340px"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="load(1)">查询</el-button>
          <el-button @click="resetFilters">重置</el-button>
        </el-form-item>
      </el-form>

      <el-table :data="rows" v-loading="loading" @expand-change="(row) => { loadTimeline(row); newMarker.markRowSeen(row) }">
        <!-- 左侧展开：流转流程时间线 -->
        <el-table-column type="expand">
          <template #default="{ row }">
            <div class="flow-expand" v-loading="expandLoading[row.id]">
              <div class="flow-title">流转记录（当前步骤已高亮）</div>
              <el-timeline v-if="timelineMap[row.id] && timelineMap[row.id].length">
                <el-timeline-item v-for="(t, i) in timelineMap[row.id]" :key="i"
                  :type="i === timelineMap[row.id].length - 1 ? 'primary' : undefined"
                  :hollow="i !== timelineMap[row.id].length - 1"
                  :timestamp="t.time" placement="top">
                  <div class="flow-node" :class="{ current: i === timelineMap[row.id].length - 1 }">
                    【{{ t.node }}】{{ t.role }}：{{ t.content }}
                  </div>
                </el-timeline-item>
              </el-timeline>
              <el-empty v-else-if="!expandLoading[row.id]" description="暂无流转记录" :image-size="50" />
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="order_no" label="工单编号" width="150" />
        <el-table-column prop="title" label="工单内容" min-width="180" show-overflow-tooltip />
        <el-table-column label="分类" width="80">
          <template #default="{ row }">{{ categoryLabel(row.category) }}</template>
        </el-table-column>
        <el-table-column prop="level" label="等级" width="70" />
        <el-table-column label="二级分类" width="100" show-overflow-tooltip>
          <template #default="{ row }">
            <!-- 驳回重走后这里展示最终处理完成的分类（sub_category 即最终值） -->
            {{ row.sub_category || '-' }}
          </template>
        </el-table-column>
        <el-table-column prop="region" label="辖区" width="90" show-overflow-tooltip>
          <template #default="{ row }">{{ row.region || '-' }}</template>
        </el-table-column>
        <el-table-column label="分派部门" width="100" show-overflow-tooltip>
          <template #default="{ row }">{{ row.department || '-' }}</template>
        </el-table-column>
        <el-table-column label="承办街道办" width="110" show-overflow-tooltip>
          <template #default="{ row }">{{ row.street_office || '-' }}</template>
        </el-table-column>
        <el-table-column label="工单创建时间" width="160">
          <template #default="{ row }">{{ fmtTime(row.create_time) }}</template>
        </el-table-column>
        <el-table-column label="工单完成时间" width="160">
          <!-- 未完成工单（close_time 为空）按归档时间口径展示 -->
          <template #default="{ row }">{{ fmtTime(row.close_time || row.archive_time) }}</template>
        </el-table-column>
        <el-table-column label="归档时间" width="160">
          <template #default="{ row }">{{ fmtTime(row.archive_time) }}</template>
        </el-table-column>
        <el-table-column label="是否完成" width="90">
          <template #default="{ row }">
            <el-tag :type="row.is_completed === 1 ? 'success' : 'danger'" size="small">
              {{ row.is_completed === 1 ? '已完成' : '未完成' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="80" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openDetail(row)">查看</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination class="pagination" layout="total, prev, pager, next" :total="total"
        :page-size="pageSize" :current-page="page" @current-change="load" />
    </el-card>

    <!-- 归档工单只读详情弹窗：全字段展示，不可修改 -->
    <el-dialog v-model="detailVisible" :title="'归档工单详情 ' + (detail.order_no || '')" width="1000px" top="4vh">
      <el-descriptions v-if="detail.id" :column="2" border size="small" v-loading="detailLoading">
        <el-descriptions-item label="工单编号">{{ detail.order_no || '-' }}</el-descriptions-item>
        <el-descriptions-item label="是否完成">
          <el-tag :type="detail.is_completed === 1 ? 'success' : 'danger'" size="small">
            {{ detail.is_completed === 1 ? '已完成' : '未完成' }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="工单状态">
          <el-tag type="info" size="small">{{ detail.status || '-' }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="归档类型">{{ archiveTypeLabel(detail.archive_type) }}</el-descriptions-item>
        <el-descriptions-item label="工单内容" :span="2">{{ detail.title || '-' }}</el-descriptions-item>
        <el-descriptions-item label="诉求详情" :span="2">{{ detail.content || '-' }}</el-descriptions-item>
        <el-descriptions-item label="一级分类">{{ categoryLabel(detail.category) }}</el-descriptions-item>
        <el-descriptions-item label="二级分类">{{ detail.sub_category || '-' }}</el-descriptions-item>
        <el-descriptions-item label="分派部门">{{ detail.department || '-' }}</el-descriptions-item>
        <el-descriptions-item label="承办街道办">{{ detail.street_office || '-' }}</el-descriptions-item>
        <el-descriptions-item label="区县">{{ detail.county_name || '-' }}</el-descriptions-item>
        <el-descriptions-item label="辖区">{{ detail.region || '-' }}</el-descriptions-item>
        <el-descriptions-item label="工单等级">{{ detail.level || '-' }}</el-descriptions-item>
        <el-descriptions-item label="关注次数">{{ detail.attention_count ?? '-' }}</el-descriptions-item>
        <el-descriptions-item label="事件类型">{{ detail.event_type || '-' }}</el-descriptions-item>
        <el-descriptions-item label="置信度">{{ detail.confidence != null ? Number(detail.confidence).toFixed(4) : '-' }}</el-descriptions-item>
        <el-descriptions-item label="命中关键词">{{ detail.hit_keywords || '-' }}</el-descriptions-item>
        <el-descriptions-item label="需人工复核">{{ detail.need_review === 1 ? '是' : '否' }}</el-descriptions-item>
        <el-descriptions-item label="处置标签">{{ disposeTagLabel(detail.dispose_tag) }}</el-descriptions-item>
        <el-descriptions-item label="处置方案" :span="2">{{ detail.dispose_plan || '-' }}</el-descriptions-item>
        <el-descriptions-item label="处置结果" :span="2">{{ detail.dispose_result || '-' }}</el-descriptions-item>
        <el-descriptions-item label="处置人">{{ detail.dispose_user_name || '-' }}</el-descriptions-item>
        <el-descriptions-item label="处置时间">{{ fmtTime(detail.dispose_time) }}</el-descriptions-item>
        <el-descriptions-item label="部门审核状态">{{ detail.dept_audit_status || '-' }}</el-descriptions-item>
        <el-descriptions-item label="部门审核备注">{{ detail.dept_audit_remark || '-' }}</el-descriptions-item>
        <el-descriptions-item label="终审状态">{{ detail.final_audit_status || '-' }}</el-descriptions-item>
        <el-descriptions-item label="终审人">{{ detail.final_audit_user_name || '-' }}</el-descriptions-item>
        <el-descriptions-item label="终审时间">{{ fmtTime(detail.final_audit_time) }}</el-descriptions-item>
        <el-descriptions-item label="终审意见">{{ detail.final_audit_remark || '-' }}</el-descriptions-item>
        <el-descriptions-item label="工单创建时间">{{ fmtTime(detail.create_time) }}</el-descriptions-item>
        <el-descriptions-item label="工单完成时间">{{ fmtTime(detail.close_time || detail.archive_time) }}</el-descriptions-item>
        <el-descriptions-item label="归档时间">{{ fmtTime(detail.archive_time) }}</el-descriptions-item>
        <el-descriptions-item label="是否超时">{{ detail.is_overdue === 1 ? '超时' : '未超时' }}</el-descriptions-item>
        <el-descriptions-item label="延期次数">{{ detail.delay_count ?? '-' }}</el-descriptions-item>
        <el-descriptions-item label="重开次数">{{ detail.reopen_count ?? '-' }}</el-descriptions-item>
      </el-descriptions>
      <!-- 关联子诉求 -->
      <div class="appeal-list-title">关联子诉求（{{ (detail.appeals || []).length }} 条）</div>
      <el-table :data="detail.appeals || []" border size="small" max-height="220">
        <el-table-column prop="appeal_no" label="诉求编号" width="180" />
        <el-table-column prop="content" label="诉求内容" min-width="200" show-overflow-tooltip />
        <el-table-column prop="location" label="地点" min-width="140" show-overflow-tooltip />
        <el-table-column prop="citizen_name" label="市民名称" width="90" />
        <el-table-column prop="citizen_phone" label="联系电话" width="120" />
        <el-table-column label="提交时间" width="150">
          <template #default="{ row }">{{ fmtTime(row.submit_time) }}</template>
        </el-table-column>
      </el-table>
      <template #footer>
        <el-button type="primary" @click="detailVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </AdminLayout>
</template>

<script setup>
import { captureContentScroll, restoreContentScroll } from '../../shared/contentScroll'
import { ref, reactive, onMounted, onBeforeUnmount } from 'vue'
import AdminLayout from '../components/AdminLayout.vue'
import { getOrders, getOrderTimeline, getOrderDetail } from '../api/order'
import { getPrimaries } from '../api/classify'
import { markModuleRead, getLastViewed } from '../api/unread'
import { useNewMarker } from '../../shared/useNewMarker'

// 终审归档"新"角标：复用工单游标（新归档才会点亮；列表返回 archive_time 字段，判新用归档时间而非 update_time）
const newMarker = useNewMarker('final_audit', {
  getLastViewed: (m) => getLastViewed(m),
  markRead: (m) => markModuleRead(m)
}, ['archive_time'])

// ---- 行展开流转时间线（懒加载，最后一条=当前步骤高亮，与工单管理一致） ----
const timelineMap = reactive({})
const expandLoading = reactive({})
const loadTimeline = async (row) => {
  if (timelineMap[row.id] || expandLoading[row.id]) return
  expandLoading[row.id] = true
  try {
    const data = await getOrderTimeline(row.id)
    timelineMap[row.id] = data.timeline || []
  } catch { timelineMap[row.id] = [] } finally { expandLoading[row.id] = false }
}

const primaries = ref([])
const primaryMap = reactive({})
const loadDict = async () => {
  try {
    const list = await getPrimaries()
    primaries.value = list || []
    ;(list || []).forEach((p) => { primaryMap[p.code] = p.name })
  } catch { /* ignore */ }
}
const categoryLabel = (c) => primaryMap[c] || c || '-'

const fmtTime = (t) => (t ? String(t).replace('T', ' ').slice(0, 19) : '-')

const loading = ref(false)
const rows = ref([])
const total = ref(0)
const page = ref(1)
const pageSize = 20
const filters = reactive({ orderNo: '', category: '' })
// 时间段筛选：[开始, 结束]，查询创建与完成时间均落在区间内的归档工单
const timeRange = ref(null)

// ---- 只读详情弹窗（全字段，不可修改） ----
const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = reactive({})

/** 打开只读详情弹窗：getOrderDetail 返回工单全量字段 + 关联子诉求 */
const openDetail = async (row) => {
  detailVisible.value = true
  detailLoading.value = true
  Object.keys(detail).forEach(k => delete detail[k])
  try {
    const d = await getOrderDetail(row.id)
    Object.assign(detail, d)
  } finally {
    detailLoading.value = false
  }
}

/** 归档类型翻译：FINAL=终审归档 MANUAL=手动归档 REJECT=驳回归档 */
const archiveTypeLabel = (t) => ({ FINAL: '终审归档', MANUAL: '手动归档', REJECT: '驳回归档' }[t] || t || '-')

/** 处置标签翻译：RESOLVED=已解决 PART_RESOLVED=部分解决 UNRESOLVED=未解决 */
const disposeTagLabel = (t) => ({ RESOLVED: '已解决', PART_RESOLVED: '部分解决', UNRESOLVED: '未解决' }[t] || t || '-')

const load = async (p = page.value) => {
  page.value = p
  loading.value = true
  const y = captureContentScroll()
  try {
    const data = await getOrders({
      order_no: filters.orderNo || undefined,
      category: filters.category || undefined,
      time_start: (timeRange.value && timeRange.value[0]) || undefined,
      time_end: (timeRange.value && timeRange.value[1]) || undefined,
      archived: 1,
      page: p,
      page_size: pageSize
    })
    rows.value = data.list || []
    newMarker.decorate(rows.value)
    newMarker.reportRead()
    total.value = data.total || 0
  } finally {
    loading.value = false
    await restoreContentScroll(y)
  }
}

const resetFilters = () => {
  filters.orderNo = ''
  filters.category = ''
  timeRange.value = null
  load(1)
}

onMounted(async () => {
  await newMarker.init() // 先取游标快照，再加载列表（判新基准稳定）
  loadDict(); load()
})
onBeforeUnmount(() => { newMarker.reportRead() })
</script>

<style scoped>
.filter-form { margin-bottom: 4px; }
.pagination { margin-top: 16px; justify-content: flex-end; }
.flow-expand { padding: 4px 12px; }
.flow-title { font-weight: 600; margin-bottom: 8px; color: var(--el-text-color-primary); }
.flow-node { font-size: 13px; color: var(--el-text-color-regular); }
.flow-node.current { color: var(--el-color-primary); font-weight: 600; }
.appeal-list-title { font-weight: 600; margin: 14px 0 8px; color: var(--el-text-color-primary); }
</style>
