<template>
  <!-- 通知公告（部门端，通知公告模块）：Tab1 收到的通知（管理员/其他部门名义发给我部门）/ Tab2 我发布的（编辑、下架） -->
  <StaffLayout title="通知公告">
    <div class="page-body">
      <el-card shadow="never">
        <el-tabs v-model="activeTab" @tab-change="onTabChange">
          <!-- ========== Tab1 收到的通知 ========== -->
          <el-tab-pane label="收到的通知" name="received">
            <el-form inline class="filter-form">
              <el-form-item label="发布者">
                <el-select v-model="recvFilters.publisher" clearable placeholder="全部发布者" style="width: 170px" @change="loadReceived(1)">
                  <el-option label="管理员" value="ADMIN" />
                  <el-option v-for="d in deptOptions" :key="d.code" :label="d.name" :value="'DEPT:' + d.code" />
                </el-select>
              </el-form-item>
              <el-form-item label="发布日期">
                <el-date-picker v-model="recvFilters.dateRange" type="daterange" clearable
                  value-format="YYYY-MM-DD" start-placeholder="开始日期" end-placeholder="结束日期"
                  style="width: 240px" @change="loadReceived(1)" @clear="loadReceived(1)" />
              </el-form-item>
              <el-form-item>
                <el-button @click="loadReceived(1)">查询</el-button>
              </el-form-item>
            </el-form>

            <el-table :data="recvRows" v-loading="recvLoading">
              <el-table-column label="标题" min-width="220" show-overflow-tooltip>
                <template #default="{ row }">
                  <span class="unread-dot" v-if="row.isRead === 0" />
                  <span :class="{ 'unread-title': row.isRead === 0 }">{{ row.title }}</span>
                </template>
              </el-table-column>
              <el-table-column label="发布者" width="130">
                <template #default="{ row }">{{ row.publisher || '-' }}</template>
              </el-table-column>
              <el-table-column label="状态" width="90">
                <template #default="{ row }">
                  <el-tag v-if="row.isRead === 0" type="danger" size="small" effect="plain">未读</el-tag>
                  <el-tag v-else type="info" size="small" effect="plain">已读</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="发布时间" width="170">
                <template #default="{ row }">{{ fmtTime(row.createTime) }}</template>
              </el-table-column>
              <el-table-column label="操作" width="80" fixed="right">
                <template #default="{ row }">
                  <el-button link type="primary" size="small" @click="openRecvDetail(row)">查看</el-button>
                </template>
              </el-table-column>
            </el-table>
            <el-pagination class="pagination" layout="total, prev, pager, next" :total="recvTotal"
              :page-size="pageSize" :current-page="recvPage" @current-change="loadReceived" />
          </el-tab-pane>

          <!-- ========== Tab2 我发布的 ========== -->
          <el-tab-pane label="我发布的" name="published">
            <el-form inline class="filter-form">
              <el-form-item label="发布日期">
                <el-date-picker v-model="pubFilters.dateRange" type="daterange" clearable
                  value-format="YYYY-MM-DD" start-placeholder="开始日期" end-placeholder="结束日期"
                  style="width: 240px" @change="loadPublished(1)" @clear="loadPublished(1)" />
              </el-form-item>
              <el-form-item>
                <el-button @click="loadPublished(1)">查询</el-button>
                <el-button type="primary" plain @click="openCreate">发布通知</el-button>
              </el-form-item>
            </el-form>

            <el-table :data="pubRows" v-loading="pubLoading">
              <el-table-column prop="title" label="标题" min-width="200" show-overflow-tooltip />
              <el-table-column label="接收对象" min-width="180" show-overflow-tooltip>
                <template #default="{ row }">{{ row.targetSummary || '-' }}</template>
              </el-table-column>
              <el-table-column label="状态" width="90">
                <template #default="{ row }">
                  <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">{{ row.status === 1 ? '展示中' : '已下架' }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="发布时间" width="170">
                <template #default="{ row }">{{ fmtTime(row.createTime) }}</template>
              </el-table-column>
              <el-table-column label="操作" width="170" fixed="right">
                <template #default="{ row }">
                  <el-button link type="primary" size="small" @click="openPubDetail(row)">查看</el-button>
                  <template v-if="row.status === 1">
                    <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
                    <el-button link type="danger" size="small" @click="offShelf(row)">下架</el-button>
                  </template>
                  <el-button v-else link type="success" size="small" @click="openRepublish(row)">重新发布</el-button>
                </template>
              </el-table-column>
            </el-table>
            <el-pagination class="pagination" layout="total, prev, pager, next" :total="pubTotal"
              :page-size="pageSize" :current-page="pubPage" @current-change="loadPublished" />
          </el-tab-pane>
        </el-tabs>
      </el-card>
    </div>
  </StaffLayout>

  <!-- 收到的通知详情（点开自动记已读，红点减一） -->
  <el-dialog v-model="recvDetailVisible" :title="recvDetail?.title" width="600px" top="10vh">
    <div class="notice-detail">
      <div class="detail-meta">
        <span>发布者：{{ recvDetail?.publisher || '-' }}</span>
        <span v-if="recvDetail?.createTime">发布时间：{{ fmtTime(recvDetail.createTime) }}</span>
      </div>
      <el-divider />
      <div class="detail-content" v-if="recvDetail?.content">
        <pre class="detail-pre">{{ recvDetail.content }}</pre>
      </div>
      <div class="detail-empty" v-else>暂无正文内容</div>
    </div>
    <template #footer>
      <el-button @click="recvDetailVisible = false">关闭</el-button>
    </template>
  </el-dialog>

  <!-- 我发布的详情（本地行数据展示，不调详情接口） -->
  <el-dialog v-model="pubDetailVisible" :title="pubDetail?.title" width="600px" top="10vh">
    <div class="notice-detail">
      <div class="detail-meta">
        <span>接收对象：{{ pubDetail?.targetSummary || '-' }}</span>
        <span v-if="pubDetail?.createTime">发布时间：{{ fmtTime(pubDetail.createTime) }}</span>
      </div>
      <el-divider />
      <div class="detail-content" v-if="pubDetail?.content">
        <pre class="detail-pre">{{ pubDetail.content }}</pre>
      </div>
      <div class="detail-empty" v-else>暂无正文内容</div>
    </div>
    <template #footer>
      <el-button @click="pubDetailVisible = false">关闭</el-button>
    </template>
  </el-dialog>

  <!-- 发布/编辑/重新发布弹窗（层级限制：仅街道办 + 市民公开，名义固定本部门） -->
  <el-dialog v-model="formVisible" :title="{ create: '发布通知', edit: '编辑通知', republish: '重新发布通知' }[mode]" width="680px" top="6vh">
    <el-form :model="form" label-width="90px">
      <el-form-item label="标题" required>
        <el-input v-model="form.title" placeholder="请输入通知标题" maxlength="200" show-word-limit />
      </el-form-item>
      <el-form-item label="正文" required>
        <el-input v-model="form.content" type="textarea" :rows="8" placeholder="请输入通知正文（纯文本，展示格式与市民端公告一致）" />
      </el-form-item>
      <el-form-item label="接收街道办">
        <div class="target-block">
          <div class="street-toolbar">
            <el-checkbox v-model="streetAll" :indeterminate="streetIndeterminate" @change="onStreetAll">全部街道办</el-checkbox>
            <span class="street-count" v-if="form.streets.length">已选 {{ form.streets.length }} 个街道办</span>
          </div>
          <!-- 两级选择：左区县列表（点击展开/收起），右该区县街道办勾选 -->
          <div class="street-panel">
            <div class="county-list">
              <div v-for="g in streetGroups" :key="g.county" class="county-item"
                :class="{ active: activeCounty === g.county }">
                <el-checkbox class="county-check"
                  :model-value="countyChecked(g.county)"
                  :indeterminate="countyIndeterminate(g.county)"
                  @change="(v) => toggleCountyAll(g.county, v)" />
                <span class="county-name" @click="toggleCounty(g.county)">{{ g.county }}</span>
                <span class="county-badge" v-if="countySelected(g.county)">{{ countySelected(g.county) }}</span>
              </div>
            </div>
            <div class="street-list">
              <template v-if="activeCounty">
                <el-checkbox-group v-model="form.streets" @change="onStreetChange">
                  <el-checkbox v-for="s in activeStreets" :key="s" :value="s">{{ s }}</el-checkbox>
                </el-checkbox-group>
              </template>
              <div v-else class="street-placeholder">点击左侧区县展示街道办</div>
            </div>
          </div>
          <!-- 已选汇总（可单个移除） -->
          <div class="street-selected" v-if="form.streets.length">
            <el-tag v-for="s in form.streets" :key="s" closable size="small" class="selected-tag" @close="removeStreet(s)">{{ s }}</el-tag>
            <el-button link type="danger" size="small" @click="form.streets = []; onStreetChange()">清空</el-button>
          </div>
        </div>
      </el-form-item>
      <el-form-item label="市民公开">
        <el-checkbox v-model="form.publicFlag">同步发布到市民端通知公告（全员可见）</el-checkbox>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="formVisible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="save">{{ { create: '发布', edit: '保存', republish: '重新发布' }[mode] }}</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import StaffLayout from '../components/StaffLayout.vue'
import { getPrimaries } from '../api/classify'
import { getRegionChildren, getCounties } from '../api/region'
import {
  getReceivedNotices, getPublishedNotices, getNoticeDetail,
  createNotice, updateNotice, offShelfNotice, republishNotice
} from '../api/notice'

const pageSize = 10

// ---- 下拉数据（发布者=管理员/56部门；56=7区县×8分类，前端按区县+分类本地组装） ----
const primaries = ref([])
const countiesOfDept = ref([])
const deptOptions = computed(() =>
  countiesOfDept.value.flatMap((ct) =>
    primaries.value.map((p) => ({ code: ct.region_code + ':' + p.code, name: ct.region_name + p.name }))))
const streetGroups = ref([])
const allStreetNames = computed(() => streetGroups.value.flatMap((g) => g.streets))
const loadOptions = async () => {
  try {
    primaries.value = await getPrimaries()
    const counties = await getCounties()
    countiesOfDept.value = counties
    const childrenLists = await Promise.all(counties.map(ct => getRegionChildren(ct.region_code).catch(() => [])))
    streetGroups.value = counties
      .map((ct, i) => ({ county: ct.region_name, streets: (childrenLists[i] || []).map((s) => s.region_name) }))
      .filter((g) => g.streets.length > 0)
  } catch { /* ignore */ }
}

// ---- 街道办两级选择：点区县展开/收起，切换不丢已选 ----
const activeCounty = ref('')
const activeStreets = computed(() =>
  streetGroups.value.find((g) => g.county === activeCounty.value)?.streets || [])
const toggleCounty = (county) => { activeCounty.value = activeCounty.value === county ? '' : county }
const countySelected = (county) => {
  const streets = streetGroups.value.find((g) => g.county === county)?.streets || []
  return streets.filter((s) => form.streets.includes(s)).length
}
// 区县是否全选（街道办全部在已选里，勾选框状态由已选列表派生）
const countyChecked = (county) => {
  const streets = streetGroups.value.find((g) => g.county === county)?.streets || []
  return streets.length > 0 && streets.every((s) => form.streets.includes(s))
}
// 区县是否半选（部分在已选里）
const countyIndeterminate = (county) => {
  const streets = streetGroups.value.find((g) => g.county === county)?.streets || []
  const n = streets.filter((s) => form.streets.includes(s)).length
  return n > 0 && n < streets.length
}
// 勾选/取消整个区县：并入或移出该区县全部街道办，不影响其他区县
const toggleCountyAll = (county, checked) => {
  const streets = streetGroups.value.find((g) => g.county === county)?.streets || []
  form.streets = checked
    ? [...new Set([...form.streets, ...streets])]
    : form.streets.filter((s) => !streets.includes(s))
  onStreetChange()
}

const fmtTime = (t) => (t ? String(t).replace('T', ' ').slice(0, 19) : '-')

// ========== Tab1 收到的通知 ==========
const recvLoading = ref(false)
const recvRows = ref([])
const recvTotal = ref(0)
const recvPage = ref(1)
const recvFilters = reactive({ publisher: '', dateRange: null })

const loadReceived = async (p = recvPage.value) => {
  recvPage.value = p
  recvLoading.value = true
  try {
    const data = await getReceivedNotices({
      publisher_type: recvFilters.publisher ? recvFilters.publisher.split(':')[0] : undefined,
      // 56部门口径：publisherKey 形如「DEPT:130302:URBAN」，复合键整体透传（不能按冒号截断）
      publisher_dept: recvFilters.publisher?.startsWith('DEPT:') ? recvFilters.publisher.replace(/^DEPT:/, '') : undefined,
      start_date: recvFilters.dateRange?.[0] || undefined,
      end_date: recvFilters.dateRange?.[1] || undefined,
      page: p,
      size: pageSize
    })
    recvRows.value = data.list || []
    recvTotal.value = data.total || 0
  } finally {
    recvLoading.value = false
  }
}

// 收到的详情：点开自动记已读（后端幂等），本地行置已读并刷新菜单红点
const recvDetailVisible = ref(false)
const recvDetail = ref(null)
const openRecvDetail = async (row) => {
  try {
    const detail = await getNoticeDetail(row.id)
    recvDetail.value = detail
    recvDetailVisible.value = true
    if (row.isRead === 0) {
      row.isRead = 1
      window.dispatchEvent(new Event('unread:refresh'))
    }
  } catch { /* ignore */ }
}

// ========== Tab2 我发布的 ==========
const pubLoading = ref(false)
const pubRows = ref([])
const pubTotal = ref(0)
const pubPage = ref(1)
const pubFilters = reactive({ dateRange: null, keyword: '' })

const loadPublished = async (p = pubPage.value) => {
  pubPage.value = p
  pubLoading.value = true
  try {
    const data = await getPublishedNotices({
      keyword: pubFilters.keyword || undefined,
      start_date: pubFilters.dateRange?.[0] || undefined,
      end_date: pubFilters.dateRange?.[1] || undefined,
      page: p,
      size: pageSize
    })
    pubRows.value = data.list || []
    pubTotal.value = data.total || 0
  } finally {
    pubLoading.value = false
  }
}

// 我发布的详情：行数据已含正文与接收对象，本地展示即可
const pubDetailVisible = ref(false)
const pubDetail = ref(null)
const openPubDetail = (row) => { pubDetail.value = row; pubDetailVisible.value = true }

// ---- 发布/编辑/重新发布弹窗（仅街道办 + 市民公开；回填用行数据，不调详情接口） ----
const formVisible = ref(false)
const mode = ref('create')
const saving = ref(false)
const editingId = ref(null)
const form = reactive({ title: '', content: '', streets: [], publicFlag: false })
const streetAll = ref(false)
const streetIndeterminate = ref(false)

const onStreetAll = (v) => {
  form.streets = v ? [...allStreetNames.value] : []
  onStreetChange()
}
const onStreetChange = () => {
  streetAll.value = allStreetNames.value.length > 0 && form.streets.length === allStreetNames.value.length
  streetIndeterminate.value = form.streets.length > 0 && !streetAll.value
}
const removeStreet = (s) => {
  form.streets = form.streets.filter((x) => x !== s)
  onStreetChange()
}

const openCreate = () => {
  mode.value = 'create'
  editingId.value = null
  form.title = ''
  form.content = ''
  form.streets = []
  form.publicFlag = false
  streetAll.value = false
  streetIndeterminate.value = false
  activeCounty.value = ''
  formVisible.value = true
}

// 编辑（展示中）与重新发布（已下架）共用回填逻辑，仅 mode 不同
const fillFormFromRow = (row) => {
  form.title = row.title || ''
  form.content = row.content || ''
  form.streets = (row.targets || []).filter((t) => t.targetType === 'STREET').map((t) => t.targetCode)
  form.publicFlag = (row.targets || []).some((t) => t.targetType === 'PUBLIC')
  onStreetChange()
  activeCounty.value = ''
  formVisible.value = true
}

const openEdit = (row) => {
  mode.value = 'edit'
  editingId.value = row.id
  fillFormFromRow(row)
}

// 重新发布：打开弹窗预填原内容/接收对象，可改后重新发布（全员重置未读）
const openRepublish = (row) => {
  mode.value = 'republish'
  editingId.value = row.id
  fillFormFromRow(row)
}

const save = async () => {
  if (!form.title.trim()) { ElMessage.warning('请输入通知标题'); return }
  if (!form.content.trim()) { ElMessage.warning('请输入通知正文'); return }
  if (form.streets.length === 0 && !form.publicFlag) {
    ElMessage.warning('请至少选择一个接收对象'); return
  }
  const payload = {
    title: form.title.trim(),
    content: form.content,
    targets: { streets: form.streets, public: form.publicFlag }
  }
  saving.value = true
  try {
    if (mode.value === 'edit') {
      await updateNotice(editingId.value, payload)
      ElMessage.success('通知已更新')
    } else if (mode.value === 'republish') {
      await republishNotice(editingId.value, payload)
      ElMessage.success('已重新发布，接收方将重新收到未读提醒')
    } else {
      await createNotice(payload)
      ElMessage.success('通知已发布')
    }
    formVisible.value = false
    loadPublished(1)
  } finally {
    saving.value = false
  }
}

const offShelf = async (row) => {
  try {
    await ElMessageBox.confirm(`确定下架通知「${row.title}」？下架后接收方将不再显示。`, '下架确认', { type: 'warning' })
  } catch { return }
  try {
    await offShelfNotice(row.id)
    ElMessage.success('已下架')
    loadPublished(pubPage.value)
  } catch { /* ignore */ }
}

// ========== Tab 切换 ==========
const activeTab = ref('received')
const onTabChange = (name) => {
  if (name === 'published' && pubRows.value.length === 0 && !pubLoading.value) {
    loadPublished(1)
  }
}

onMounted(() => {
  loadOptions()
  loadReceived(1)
})
</script>

<style scoped>
.page-body { }
.filter-form { margin-bottom: 4px; }
.pagination { margin-top: 16px; justify-content: flex-end; }

/* 未读行：标题前橙色圆点（el-badge is-dot，type=warning）+ 加粗 */
.unread-badge {
  display: inline-flex;
  align-items: center;
  margin-right: 6px;
  vertical-align: middle;
}
.unread-badge :deep(.el-badge__content) {
  position: static;
  top: auto;
  left: auto;
  transform: none;
}
.unread-title { font-weight: 600; color: #18181b; }

/* 接收街道办多选块 */
.target-block { width: 100%; }
.target-block :deep(.el-checkbox-group) { display: flex; flex-wrap: wrap; gap: 2px 4px; margin-left: 8px; }

/* 街道办两级选择：左区县 / 右街道办 */
.street-toolbar { display: flex; align-items: center; gap: 12px; }
.street-count { font-size: 12px; color: #8e8e93; }
.street-panel {
  display: flex;
  width: 100%;
  border: 1px solid #e5e5e5;
  border-radius: 6px;
  margin-top: 6px;
  overflow: hidden;
}
.county-list {
  width: 130px;
  flex-shrink: 0;
  max-height: 200px;
  overflow-y: auto;
  border-right: 1px solid #f0f0ee;
  background: #fafafa;
}
.county-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 10px;
  font-size: 13px;
  color: #52525b;
  cursor: pointer;
  transition: background 0.15s;
}
.county-item:hover { background: #f4f4f2; }
.county-item.active { background: #18181b; color: #fff; font-weight: 500; }
/* 区县行勾选框：状态由已选街道办派生，点击不触发展开（展开点击在名称上） */
.county-check { margin-right: 4px; height: auto; }
.county-name { flex: 1; cursor: pointer; }
/* 激活行（深色底）上勾选框反色，保证勾选状态可见 */
.county-item.active .county-check {
  --el-checkbox-checked-bg-color: #fff;
  --el-checkbox-checked-input-border-color: #fff;
  --el-checkbox-checked-icon-color: #18181b;
  --el-checkbox-indeterminate-bg-color: #fff;
  --el-checkbox-indeterminate-input-border-color: #fff;
  --el-checkbox-indeterminate-icon-color: #18181b;
}
.county-badge {
  font-size: 11px;
  background: #f56c6c;
  color: #fff;
  border-radius: 8px;
  padding: 0 5px;
  line-height: 16px;
}
.street-list {
  flex: 1;
  max-height: 200px;
  overflow-y: auto;
  padding: 6px 10px;
}
.street-list :deep(.el-checkbox) { margin-right: 10px; }
.street-placeholder {
  color: #a1a1aa;
  font-size: 13px;
  text-align: center;
  padding: 70px 0;
}

/* 已选汇总 */
.street-selected {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px;
  margin-top: 8px;
}
.selected-tag { margin: 0; }

/* 详情弹窗（与市民端公告详情一致的阅读样式） */
.notice-detail { padding: 4px 0; }
.detail-meta { display: flex; flex-wrap: wrap; gap: 10px 18px; font-size: 13px; color: #8e8e93; }
.detail-content { line-height: 1.8; }
.detail-pre {
  font-family: inherit;
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 14px;
  color: #18181b;
  margin: 0;
}
.detail-empty { color: #8e8e93; font-size: 14px; text-align: center; padding: 20px 0; }
</style>
