<template>
  <!-- 管理端通知公告（通知公告模块）：上级对下级定向通知，管理员可对部门端/街道办端/市民端发布 -->
  <AdminLayout title="通知公告">
    <el-card shadow="never">
      <el-form inline class="filter-form">
        <el-form-item label="发布者">
          <el-select v-model="filters.publisher" clearable placeholder="全部发布者" style="width: 160px" @change="load(1)">
            <el-option label="管理员" value="ADMIN" />
            <el-option v-for="d in deptOptions" :key="d.code" :label="d.name" :value="'DEPT:' + d.code" />
          </el-select>
        </el-form-item>
        <el-form-item label="发布日期">
          <el-date-picker v-model="filters.dateRange" type="daterange" clearable
            value-format="YYYY-MM-DD" start-placeholder="开始日期" end-placeholder="结束日期"
            style="width: 240px" @change="load(1)" @clear="load(1)" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="filters.status" clearable placeholder="全部状态" style="width: 120px" @change="load(1)">
            <el-option label="展示中" :value="1" />
            <el-option label="已下架" :value="0" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="load(1)">查询</el-button>
          <el-button @click="resetFilters">重置</el-button>
          <el-button type="primary" plain @click="openCreate">发布通知</el-button>
        </el-form-item>
      </el-form>

      <el-table :data="rows" v-loading="loading">
        <el-table-column prop="title" label="标题" min-width="200" show-overflow-tooltip />
        <el-table-column label="发布者" width="110">
          <template #default="{ row }">{{ publisherLabel(row) }}</template>
        </el-table-column>
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
            <el-button link type="primary" size="small" @click="showDetail(row)">查看</el-button>
            <template v-if="row.status === 1">
              <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
              <el-button link type="danger" size="small" @click="offShelf(row)">下架</el-button>
            </template>
            <el-button v-else link type="success" size="small" @click="openRepublish(row)">重新发布</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination class="pagination" layout="total, prev, pager, next" :total="total"
        :page-size="pageSize" :current-page="page" @current-change="load" />
    </el-card>

    <!-- 发布/编辑/重新发布弹窗 -->
    <el-dialog v-model="formVisible" :title="{ create: '发布通知', edit: '编辑通知', republish: '重新发布通知' }[mode]" width="680px" top="6vh">
      <el-form :model="form" label-width="90px">
        <el-form-item label="标题" required>
          <el-input v-model="form.title" placeholder="请输入通知标题" maxlength="200" show-word-limit />
        </el-form-item>
        <el-form-item label="正文" required>
          <el-input v-model="form.content" type="textarea" :rows="8" placeholder="请输入通知正文（纯文本，展示格式与市民端公告一致）" />
        </el-form-item>
        <el-form-item label="发布者">
          <el-select v-model="form.publisherKey" :disabled="mode !== 'create'" style="width: 100%">
            <el-option label="管理员（本人）" value="ADMIN" />
            <el-option v-for="d in deptOptions" :key="d.code" :label="d.name + '（部门名义）'" :value="'DEPT:' + d.code" />
          </el-select>
        </el-form-item>
        <el-form-item label="接收部门">
          <div class="target-block">
            <div class="street-toolbar">
              <el-checkbox v-model="deptAll" :indeterminate="deptIndeterminate" @change="onDeptAll">全部部门</el-checkbox>
              <span class="street-count" v-if="form.depts.length">已选 {{ form.depts.length }} 个部门</span>
            </div>
            <!-- 两级选择：左区县列表（点击展开/收起），右该区县部门勾选（与街道办联动一致） -->
            <div class="street-panel">
              <div class="county-list">
                <div v-for="g in deptGroups" :key="g.county" class="county-item"
                  :class="{ active: activeDeptCounty === g.county }">
                  <el-checkbox class="county-check"
                    :model-value="deptCountyChecked(g.county)"
                    :indeterminate="deptCountyIndeterminate(g.county)"
                    @change="(v) => toggleDeptCountyAll(g.county, v)" />
                  <span class="county-name" @click="toggleDeptCounty(g.county)">{{ g.county }}</span>
                  <span class="county-badge" v-if="deptCountySelected(g.county)">{{ deptCountySelected(g.county) }}</span>
                </div>
              </div>
              <div class="street-list">
                <template v-if="activeDeptCounty">
                  <el-checkbox-group v-model="form.depts" @change="onDeptChange">
                    <el-checkbox v-for="d in activeDeptList" :key="d.code" :value="d.code">{{ d.shortName }}</el-checkbox>
                  </el-checkbox-group>
                </template>
                <div v-else class="street-placeholder">点击左侧区县展示部门</div>
              </div>
            </div>
            <!-- 已选汇总（可单个移除） -->
            <div class="street-selected" v-if="form.depts.length">
              <el-tag v-for="code in form.depts" :key="code" closable size="small" class="selected-tag" @close="removeDept(code)">{{ deptNameOf(code) }}</el-tag>
              <el-button link type="danger" size="small" @click="form.depts = []; onDeptChange()">清空</el-button>
            </div>
          </div>
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

    <!-- 详情弹窗（展示格式与市民端公告详情一致） -->
    <el-dialog v-model="detailVisible" :title="currentNotice?.title" width="600px" top="10vh">
      <div class="notice-detail">
        <div class="detail-meta">
          <span>发布者：{{ currentNotice ? publisherLabel(currentNotice) : '-' }}</span>
          <span>接收对象：{{ currentNotice?.targetSummary || '-' }}</span>
          <span v-if="currentNotice?.createTime">发布时间：{{ fmtTime(currentNotice.createTime) }}</span>
        </div>
        <el-divider />
        <div class="detail-content" v-if="currentNotice?.content">
          <pre class="detail-pre">{{ currentNotice.content }}</pre>
        </div>
        <div class="detail-empty" v-else>暂无正文内容</div>
      </div>
    </el-dialog>
  </AdminLayout>
</template>

<script setup>
import { captureContentScroll, restoreContentScroll } from '../../shared/contentScroll'
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import AdminLayout from '../components/AdminLayout.vue'
import { getNoticeOptions, getNotices, getNoticeDetail, createNotice, updateNotice, offShelfNotice, republishNotice } from '../api/notice'

// ---- 下拉数据（56 部门 + 按区县分组的街道办） ----
const deptOptions = ref([])   // 56 平铺（发布者下拉/已选汇总反查用）
const deptGroups = ref([])    // 按区县分组的部门 [{ county, depts:[{code,name,shortName}] }]
const streetGroups = ref([])
const allStreetNames = computed(() => streetGroups.value.flatMap((g) => g.streets))
const loadOptions = async () => {
  try {
    const data = await getNoticeOptions()
    deptOptions.value = data.depts || []
    // 后端已按区县分组返回 dept_groups；shortName = 去掉区县前缀的纯部门名（右侧勾选用，与街道办"只显名"一致）
    deptGroups.value = (data.dept_groups || []).map((g) => ({
      county: g.county,
      depts: (g.depts || []).map((d) => ({
        code: d.code,
        name: d.name,
        shortName: (d.name && d.name.startsWith(g.county)) ? d.name.slice(g.county.length) : d.name
      }))
    }))
    streetGroups.value = data.streets || []
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

// ---- 列表查询 ----
const loading = ref(false)
const rows = ref([])
const total = ref(0)
const page = ref(1)
const pageSize = 20
const filters = reactive({ keyword: '', publisher: '', dateRange: null, status: null })

const load = async (p = page.value) => {
  page.value = p
  loading.value = true
  const y = captureContentScroll()
  try {
    const data = await getNotices({
      keyword: filters.keyword || undefined,
      publisher_type: filters.publisher ? filters.publisher.split(':')[0] : undefined,
      publisher_dept: filters.publisher?.startsWith('DEPT:') ? filters.publisher.replace(/^DEPT:/, '') : undefined,
      start_date: filters.dateRange?.[0] || undefined,
      end_date: filters.dateRange?.[1] || undefined,
      status: filters.status ?? undefined,
      page: p,
      size: pageSize
    })
    rows.value = data.list || []
    total.value = data.total || 0
  } finally {
    loading.value = false
    await restoreContentScroll(y)
  }
}

const resetFilters = () => {
  filters.keyword = ''
  filters.publisher = ''
  filters.dateRange = null
  filters.status = null
  load(1)
}

const publisherLabel = (row) =>
  row.publisherType === 'DEPT' ? (row.publisherDeptName || row.publisherDept || '部门') : '管理员'

const fmtTime = (t) => (t ? String(t).replace('T', ' ').slice(0, 19) : '-')

// ---- 发布/编辑/重新发布弹窗（mode: create发布 / edit编辑 / republish重新发布） ----
const formVisible = ref(false)
const mode = ref('create')
const saving = ref(false)
const editingId = ref(null)
const form = reactive({ title: '', content: '', publisherKey: 'ADMIN', depts: [], streets: [], publicFlag: false })
const deptAll = ref(false)
const deptIndeterminate = ref(false)
const streetAll = ref(false)
const streetIndeterminate = ref(false)

const onDeptAll = (v) => {
  form.depts = v ? [...allDeptCodes.value] : []
  deptIndeterminate.value = false
}
// ---- 部门两级选择：点区县展开/收起，切换不丢已选（复用街道办同款交互） ----
const activeDeptCounty = ref('')
const allDeptCodes = computed(() => deptOptions.value.map((d) => d.code))
const activeDeptList = computed(() =>
  deptGroups.value.find((g) => g.county === activeDeptCounty.value)?.depts || [])
const toggleDeptCounty = (county) => { activeDeptCounty.value = activeDeptCounty.value === county ? '' : county }
const deptCountySelected = (county) => {
  const depts = deptGroups.value.find((g) => g.county === county)?.depts || []
  return depts.filter((d) => form.depts.includes(d.code)).length
}
const deptCountyChecked = (county) => {
  const depts = deptGroups.value.find((g) => g.county === county)?.depts || []
  return depts.length > 0 && depts.every((d) => form.depts.includes(d.code))
}
const deptCountyIndeterminate = (county) => {
  const depts = deptGroups.value.find((g) => g.county === county)?.depts || []
  const n = depts.filter((d) => form.depts.includes(d.code)).length
  return n > 0 && n < depts.length
}
const toggleDeptCountyAll = (county, checked) => {
  const codes = deptGroups.value.find((g) => g.county === county)?.depts.map((d) => d.code) || []
  form.depts = checked
    ? [...new Set([...form.depts, ...codes])]
    : form.depts.filter((c) => !codes.includes(c))
  onDeptChange()
}
const onDeptChange = () => {
  deptAll.value = allDeptCodes.value.length > 0 && form.depts.length === allDeptCodes.value.length
  deptIndeterminate.value = form.depts.length > 0 && !deptAll.value
}
const removeDept = (code) => {
  form.depts = form.depts.filter((c) => c !== code)
  onDeptChange()
}
const deptNameOf = (code) => deptOptions.value.find((d) => d.code === code)?.name || code
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
  form.publisherKey = 'ADMIN'
  form.depts = []
  form.streets = []
  form.publicFlag = false
  deptAll.value = false
  deptIndeterminate.value = false
  streetAll.value = false
  streetIndeterminate.value = false
  activeCounty.value = ''
  formVisible.value = true
}

// 编辑（展示中）与重新发布（已下架）共用回填逻辑，仅 mode 不同
const fillFormFromDetail = (detail) => {
  form.title = detail.title || ''
  form.content = detail.content || ''
  form.publisherKey = detail.publisherType === 'DEPT' ? 'DEPT:' + detail.publisherDept : 'ADMIN'
  form.depts = (detail.targets || []).filter((t) => t.targetType === 'DEPT').map((t) => t.targetCode)
  form.streets = (detail.targets || []).filter((t) => t.targetType === 'STREET').map((t) => t.targetCode)
  form.publicFlag = (detail.targets || []).some((t) => t.targetType === 'PUBLIC')
  deptAll.value = deptOptions.value.length > 0 && form.depts.length === deptOptions.value.length
  deptIndeterminate.value = form.depts.length > 0 && !deptAll.value
  onStreetChange()
  activeCounty.value = ''
  activeDeptCounty.value = ''
  formVisible.value = true
}

const openEdit = async (row) => {
  try {
    const detail = await getNoticeDetail(row.id)
    mode.value = 'edit'
    editingId.value = row.id
    fillFormFromDetail(detail)
  } catch { /* ignore */ }
}

// 重新发布：打开弹窗预填原内容/接收对象，可改后重新发布（全员重置未读）
const openRepublish = async (row) => {
  try {
    const detail = await getNoticeDetail(row.id)
    mode.value = 'republish'
    editingId.value = row.id
    fillFormFromDetail(detail)
  } catch { /* ignore */ }
}

const save = async () => {
  if (!form.title.trim()) { ElMessage.warning('请输入通知标题'); return }
  if (!form.content.trim()) { ElMessage.warning('请输入通知正文'); return }
  if (form.depts.length === 0 && form.streets.length === 0 && !form.publicFlag) {
    ElMessage.warning('请至少选择一个接收对象'); return
  }
  const payload = {
    title: form.title.trim(),
    content: form.content,
    publisher_type: form.publisherKey === 'ADMIN' ? 'ADMIN' : 'DEPT',
    // 56部门口径：publisherKey 形如「DEPT:130302:URBAN」，复合键整体透传（不能按冒号截断）
    publisher_dept: form.publisherKey?.startsWith('DEPT:') ? form.publisherKey.replace(/^DEPT:/, '') : undefined,
    targets: { depts: form.depts, streets: form.streets, public: form.publicFlag }
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
    load(1)
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
    load(page.value)
  } catch { /* ignore */ }
}

// ---- 详情弹窗 ----
const detailVisible = ref(false)
const currentNotice = ref(null)
const showDetail = (row) => { currentNotice.value = row; detailVisible.value = true }

onMounted(() => {
  loadOptions()
  load()
})
</script>

<style scoped>
.filter-form { margin-bottom: 4px; }
.pagination { margin-top: 16px; justify-content: flex-end; }

/* 接收对象多选块 */
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
