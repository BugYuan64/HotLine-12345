<template>
  <!-- 通知公告（街道办端，通知公告模块）：接收上级通知 + 本街道发布（仅市民公开 PUBLIC，层级限制） -->
  <StreetLayout title="通知公告">
    <div class="page-body">
      <el-tabs v-model="activeTab" @tab-change="onTabChange">
        <!-- ==================== 收到的通知 ==================== -->
        <el-tab-pane label="收到的通知" name="received">
          <el-card shadow="never">
            <el-form inline class="filter-form">
              <el-form-item label="发布者">
                <el-select v-model="filters.publisher" clearable placeholder="全部发布者" style="width: 170px" @change="load(1)">
                  <el-option label="管理员" value="ADMIN" />
                  <el-option v-for="d in deptOptions" :key="d.code" :label="d.name" :value="'DEPT:' + d.code" />
                </el-select>
              </el-form-item>
              <el-form-item label="发布日期">
                <el-date-picker v-model="filters.dateRange" type="daterange" clearable
                  value-format="YYYY-MM-DD" start-placeholder="开始日期" end-placeholder="结束日期"
                  style="width: 240px" @change="load(1)" @clear="load(1)" />
              </el-form-item>
              <el-form-item>
                <el-button @click="load(1)">查询</el-button>
              </el-form-item>
            </el-form>

            <el-table :data="rows" v-loading="loading">
              <el-table-column label="标题" min-width="220" show-overflow-tooltip>
                <template #default="{ row }">
                  <el-badge v-if="row.isRead === 0" is-dot type="warning" class="unread-badge" />
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
                  <el-button link type="primary" size="small" @click="openDetail(row)">查看</el-button>
                </template>
              </el-table-column>
            </el-table>
            <el-pagination class="pagination" layout="total, prev, pager, next" :total="total"
              :page-size="pageSize" :current-page="page" @current-change="load" />
          </el-card>
        </el-tab-pane>

        <!-- ==================== 我发布的（仅市民公开） ==================== -->
        <el-tab-pane label="我发布的" name="published">
          <el-card shadow="never">
            <div class="toolbar">
              <el-button type="primary" @click="openPublish">发布公告（市民公开）</el-button>
              <span class="muted">街道办仅能向市民端发布公开通知（层级权限：STREET→PUBLIC）</span>
            </div>
            <el-table :data="pubRows" v-loading="pubLoading">
              <el-table-column label="标题" min-width="220" show-overflow-tooltip>
                <template #default="{ row }">{{ row.title }}</template>
              </el-table-column>
              <el-table-column label="接收对象" width="140">
                <template #default="{ row }">{{ row.targetSummary || '-' }}</template>
              </el-table-column>
              <el-table-column label="发布时间" width="170">
                <template #default="{ row }">{{ fmtTime(row.createTime) }}</template>
              </el-table-column>
              <el-table-column label="状态" width="90">
                <template #default="{ row }">
                  <el-tag v-if="row.status === 1" type="success" size="small" effect="plain">展示中</el-tag>
                  <el-tag v-else type="info" size="small" effect="plain">已下架</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="操作" width="200" fixed="right">
                <template #default="{ row }">
                  <el-button link type="primary" size="small" @click="openDetail(row)">查看</el-button>
                  <el-button v-if="row.status === 1" link type="warning" size="small" @click="handleEdit(row)">编辑</el-button>
                  <el-button v-if="row.status === 1" link type="danger" size="small" @click="handleOffShelf(row)">下架</el-button>
                  <el-button v-else link type="success" size="small" @click="handleRepublish(row)">重新发布</el-button>
                </template>
              </el-table-column>
            </el-table>
            <el-pagination class="pagination" layout="total, prev, pager, next" :total="pubTotal"
              :page-size="pageSize" :current-page="pubPage" @current-change="loadPublished" />
          </el-card>
        </el-tab-pane>
      </el-tabs>
    </div>
  </StreetLayout>

  <!-- 通知详情（点开自动记已读，红点减一） -->
  <el-dialog v-model="detailVisible" :title="detail?.title" width="600px" top="10vh">
    <div class="notice-detail">
      <div class="detail-meta">
        <span>发布者：{{ detail?.publisher || '-' }}</span>
        <span v-if="detail?.createTime">发布时间：{{ fmtTime(detail.createTime) }}</span>
      </div>
      <el-divider />
      <div class="detail-content" v-if="detail?.content">
        <pre class="detail-pre">{{ detail.content }}</pre>
      </div>
      <div class="detail-empty" v-else>暂无正文内容</div>
    </div>
    <template #footer>
      <el-button @click="detailVisible = false">关闭</el-button>
    </template>
  </el-dialog>

  <!-- 发布/编辑弹窗（仅标题+正文；接收对象固定市民公开 PUBLIC） -->
  <el-dialog v-model="publishVisible" :title="editingId ? '编辑公告' : '发布公告'" width="560px">
    <el-form label-width="80px">
      <el-form-item label="标题" required>
        <el-input v-model="publishForm.title" maxlength="200" show-word-limit placeholder="公告标题（必填）" />
      </el-form-item>
      <el-form-item label="正文" required>
        <el-input v-model="publishForm.content" type="textarea" :rows="6" placeholder="公告正文（必填）" />
      </el-form-item>
      <el-form-item label="接收对象">
        <el-tag type="success" size="small">市民公开（PUBLIC）</el-tag>
        <div class="muted">街道办层级限制：仅能向市民端发布公开通知</div>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="publishVisible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="handleSubmit">提交</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import StreetLayout from '../../staff-app/components/StreetLayout.vue'
import { getPrimaries } from '../api/classify'
import {
  getReceivedNotices, getPublishedNotices, getNoticeDetail,
  createNotice, updateNotice, offShelfNotice, republishNotice
} from '../api/notice'

const pageSize = 10
const activeTab = ref('received')

// ---- 发布者下拉（管理员 + 八大部门名义） ----
const deptOptions = ref([])
const loadOptions = async () => {
  try {
    deptOptions.value = await getPrimaries()
  } catch { /* ignore */ }
}

const fmtTime = (t) => (t ? String(t).replace('T', ' ').slice(0, 19) : '-')

// ---- 收到的通知 ----
const loading = ref(false)
const rows = ref([])
const total = ref(0)
const page = ref(1)
const filters = reactive({ publisher: '', dateRange: null, keyword: '' })

const load = async (p = page.value) => {
  page.value = p
  loading.value = true
  try {
    const data = await getReceivedNotices({
      keyword: filters.keyword || undefined,
      publisher_type: filters.publisher ? filters.publisher.split(':')[0] : undefined,
      publisher_dept: filters.publisher?.startsWith('DEPT:') ? filters.publisher.split(':')[1] : undefined,
      start_date: filters.dateRange?.[0] || undefined,
      end_date: filters.dateRange?.[1] || undefined,
      page: p,
      size: pageSize
    })
    rows.value = data.list || []
    total.value = data.total || 0
  } finally {
    loading.value = false
  }
}

// ---- 我发布的 ----
const pubLoading = ref(false)
const pubRows = ref([])
const pubTotal = ref(0)
const pubPage = ref(1)
const pubFilters = reactive({ keyword: '' })

const loadPublished = async (p = pubPage.value) => {
  pubPage.value = p
  pubLoading.value = true
  try {
    const data = await getPublishedNotices({ keyword: pubFilters.keyword || undefined, page: p, size: pageSize })
    pubRows.value = data.list || []
    pubTotal.value = data.total || 0
  } finally {
    pubLoading.value = false
  }
}

const onTabChange = (name) => {
  if (name === 'published') loadPublished(1)
}

// ---- 详情：点开自动记已读（后端幂等），本地行置已读并刷新菜单红点 ----
const detailVisible = ref(false)
const detail = ref(null)
const openDetail = async (row) => {
  try {
    const data = await getNoticeDetail(row.id)
    detail.value = data
    detailVisible.value = true
    if (row.isRead === 0) {
      row.isRead = 1
      window.dispatchEvent(new Event('unread:refresh'))
    }
  } catch { /* ignore */ }
}

// ---- 发布/编辑 ----
const publishVisible = ref(false)
const saving = ref(false)
const editingId = ref(null)
const publishForm = reactive({ title: '', content: '' })

const openPublish = () => {
  editingId.value = null
  publishForm.title = ''
  publishForm.content = ''
  publishVisible.value = true
}

const handleEdit = (row) => {
  editingId.value = row.id
  publishForm.title = row.title || ''
  publishForm.content = row.content || ''
  publishVisible.value = true
}

const handleSubmit = async () => {
  if (!publishForm.title.trim() || !publishForm.content.trim()) {
    ElMessage.warning('标题与正文不能为空')
    return
  }
  saving.value = true
  try {
    if (editingId.value) {
      await updateNotice(editingId.value, { title: publishForm.title.trim(), content: publishForm.content.trim() })
      ElMessage.success('公告已更新')
    } else {
      await createNotice({ title: publishForm.title.trim(), content: publishForm.content.trim(), targets: { public: true } })
      ElMessage.success('公告已发布（市民公开）')
    }
    publishVisible.value = false
    loadPublished(1)
  } finally {
    saving.value = false
  }
}

const handleOffShelf = async (row) => {
  try {
    await ElMessageBox.confirm(`确认下架公告「${row.title}」？下架后接收方不可见`, '下架确认', { type: 'warning' })
  } catch { return }
  await offShelfNotice(row.id)
  ElMessage.success('已下架')
  loadPublished(1)
}

const handleRepublish = async (row) => {
  try {
    await ElMessageBox.confirm(`确认重新发布「${row.title}」？将重建收件箱并清空已读`, '重新发布', { type: 'info' })
  } catch { return }
  await republishNotice(row.id, { title: row.title, content: row.content, targets: { public: true } })
  ElMessage.success('已重新发布')
  loadPublished(1)
}

onMounted(() => {
  loadOptions()
  load(1)
})
</script>

<style scoped>
.filter-form { margin-bottom: 4px; }
.toolbar { margin-bottom: 12px; display: flex; align-items: center; gap: 12px; }
.pagination { margin-top: 16px; justify-content: flex-end; }
.muted { color: #8e8e93; font-size: 13px; }

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
