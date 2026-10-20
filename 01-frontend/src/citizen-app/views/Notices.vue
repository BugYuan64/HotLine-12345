<!--
  通知公告（极简风）：套 CitizenLayout 共享布局
-->
<template>
  <CitizenLayout title="通知公告">
    <!-- 公告列表 -->
    <div class="notices-list" v-loading="loading">
      <el-empty v-if="!loading && notices.length === 0" description="暂无公告" :image-size="80" />

      <div
        v-for="item in notices"
        :key="item.id"
        class="notice-card"
        :class="{ pinned: item.topFlag === 1 }"
        @click="showDetail(item)"
      >
        <div class="card-head">
          <el-icon class="card-icon">
            <component :is="item.topFlag === 1 ? Notification : Document" />
          </el-icon>
          <div class="card-title">
            <el-tag v-if="item.topFlag === 1" size="small" effect="plain" class="pin-tag">置顶</el-tag>
            {{ item.title }}
          </div>
        </div>
        <div class="card-summary" v-if="item.summary">{{ item.summary }}</div>
        <div class="card-foot">
          <span v-if="item.publisher">
            <el-icon :size="13"><User /></el-icon>{{ item.publisher }}
          </span>
          <span>
            <el-icon :size="13"><Clock /></el-icon>{{ formatTime(item.createTime) }}
          </span>
        </div>
      </div>
    </div>

    <!-- 分页 -->
    <div class="pagination-wrap" v-if="total > pageSize">
      <el-pagination
        background
        layout="prev, pager, next, total"
        :total="total"
        :page-size="pageSize"
        :current-page="page"
        @current-change="handlePageChange"
      />
    </div>

    <!-- 公告详情弹窗 -->
    <el-dialog v-model="detailVisible" :title="currentNotice?.title" width="600px" top="10vh">
      <div class="notice-detail">
        <div class="detail-meta">
          <span v-if="currentNotice?.publisher">
            <el-icon><User /></el-icon> {{ currentNotice.publisher }}
          </span>
          <span v-if="currentNotice?.createTime">
            <el-icon><Clock /></el-icon> {{ formatTime(currentNotice.createTime) }}
          </span>
        </div>
        <el-divider />
        <div class="detail-content" v-if="currentNotice?.content">
          <pre class="detail-pre">{{ currentNotice.content }}</pre>
        </div>
        <div class="detail-empty" v-else>暂无正文内容</div>
      </div>
    </el-dialog>
  </CitizenLayout>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { formatTime } from '../../shared/format.js'
import { User, Clock, Flag, Notification, Document } from '@element-plus/icons-vue'
import CitizenLayout from '../components/CitizenLayout.vue'
import { getNotices } from '../api/notice'

// ========== 数据 ==========
const notices = ref([])
const loading = ref(false)
const page = ref(1)
const pageSize = ref(10)
const total = ref(0)

// ========== 详情弹窗 ==========
const detailVisible = ref(false)
const currentNotice = ref(null)
const showDetail = (item) => { currentNotice.value = item; detailVisible.value = true }

// ========== 时间格式化 ==========
// ========== 数据加载 ==========
const fetchNotices = async () => {
  loading.value = true
  try {
    const data = await getNotices(page.value, pageSize.value)
    notices.value = data.list || []
    total.value = data.total || 0
  } catch { notices.value = [] }
  finally { loading.value = false }
}

const handlePageChange = (p) => { page.value = p; fetchNotices() }

onMounted(fetchNotices)
</script>

<style scoped>
.search-bar { display: flex; justify-content: flex-end; margin-bottom: 12px; }

.notices-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.notice-card {
  background: #fff;
  border: 1px solid #ececec;
  border-radius: 8px;
  padding: 16px 18px;
  cursor: pointer;
  transition: border-color 0.15s, box-shadow 0.15s;
}
.notice-card:hover { border-color: #d4d4d8; box-shadow: 0 1px 4px rgba(0,0,0,0.05); }
.notice-card.pinned { border-left: 3px solid #18181b; }

.card-head {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  margin-bottom: 8px;
}
.card-icon { font-size: 20px; color: #52525b; flex-shrink: 0; margin-top: 2px; }
.card-title {
  flex: 1;
  font-size: 14px;
  font-weight: 600;
  color: #18181b;
  line-height: 1.4;
}
.pin-tag { margin-right: 6px; }
.card-summary {
  font-size: 13px;
  color: #52525b;
  margin-bottom: 10px;
  line-height: 1.5;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.card-foot {
  display: flex;
  gap: 18px;
  font-size: 12px;
  color: #8e8e93;
}
.card-foot span { display: inline-flex; align-items: center; gap: 4px; }

.pagination-wrap { display: flex; justify-content: center; padding: 16px; }

/* 弹窗内容 */
.notice-detail { padding: 4px 0; }
.detail-meta { display: flex; gap: 18px; font-size: 13px; color: #8e8e93; }
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
