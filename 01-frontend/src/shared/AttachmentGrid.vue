<template>
  <!-- 统一附件展示：图片→缩略图+点击全屏预览；文档→类型图标+文件名，点击下载到本地；可选删除 -->
  <div class="att-grid">
    <div v-for="(f, i) in files" :key="f.file_id || i" class="att-card" :title="f.file_name"
      @click="onOpen(f)">
      <img v-if="isImage(f)" :src="contentUrl(f)" class="att-thumb" :alt="f.file_name" loading="lazy" />
      <div v-else class="att-icon">
        <el-icon :size="30" :color="iconColor(f)"><Document /></el-icon>
        <span class="att-ext">{{ extOf(f) }}</span>
      </div>
      <el-icon v-if="removable" class="att-remove" @click.stop="$emit('remove', f)"><Close /></el-icon>
      <div class="att-name">{{ f.file_name }}</div>
    </div>
  </div>

  <!-- 图片全屏预览（el-image-viewer，Element Plus 全局注册） -->
  <el-image-viewer
    v-if="viewerVisible"
    :url-list="viewerUrls"
    :initial-index="viewerIndex"
    @close="viewerVisible = false"
  />
</template>

<script>
/**
 * 附件访问统一走后端内容端点（/common/file/content，?token= 携带鉴权）：
 * 解决后端 presignedUrl 返回的 /minio/{bucket}/{key} 相对路径在 Vite 开发代理/未配 nginx 反代时 404 的问题。
 * 图片附件内联预览，文件附件由后端返回 Content-Disposition: attachment 自动下载到本地。
 */
const CONTENT_PATH = '/api/v1/common/file/content'
const TOKEN_KEYS = ['citizen_token', 'admin_token', 'staff_token', 'street_token']
const readToken = () => TOKEN_KEYS.map((k) => localStorage.getItem(k)).find(Boolean) || ''

/** 附件对象 → 可访问 URL（file_id 优先；/minio/ 相对路径转为内容端点；blob/http 直链原样返回） */
export const attachmentContentUrl = (f) => {
  if (!f) return ''
  const raw = f.url || f.file_url || ''
  // 本地预览（blob:，未上传）或绝对直链（http/https）：原样返回，不走内容端点
  if (raw.startsWith('blob:') || /^https?:\/\//i.test(raw)) return raw
  const token = readToken()
  if (f.file_id) {
    return `${CONTENT_PATH}?fileId=${f.file_id}&token=${encodeURIComponent(token)}`
  }
  return contentUrlOfRaw(raw)
}

/** 原始 /minio/ 路径或直链 → 可访问 URL（供非 AttachmentGrid 场景复用，如处置凭证链接列表） */
export const contentUrlOfRaw = (rawUrl) => {
  if (!rawUrl) return ''
  if (rawUrl.startsWith('/minio/')) {
    const key = rawUrl.split('/').slice(2).join('/')
    return `${CONTENT_PATH}?url=${encodeURIComponent(key)}&token=${encodeURIComponent(readToken())}`
  }
  return rawUrl
}
</script>

<script setup>
import { ref } from 'vue'
import { Document, Close } from '@element-plus/icons-vue'

const props = defineProps({
  /** 附件列表：[{ file_id?, file_name, url 或 file_url, file_type? }]；
   *  file_id 存在时走后端内容端点；否则 url/file_url 为 /minio/ 相对路径时同样转内容端点；
   *  blob:/http(s) 直链（未上传预览）原样使用 */
  files: { type: Array, default: () => [] },
  /** 是否显示删除按钮（市民端选择文件后可移除） */
  removable: { type: Boolean, default: false }
})
defineEmits(['remove'])

const IMG_RE = /\.(jpe?g|png|gif|bmp|webp)$/i
const isImage = (f) =>
  (f.file_type || f.raw?.type || '').startsWith('image/') || IMG_RE.test(f.file_name || '')

const extOf = (f) => {
  const m = (f.file_name || '').match(/\.([a-z0-9]+)$/i)
  return m ? m[1].toUpperCase() : 'FILE'
}
const iconColor = (f) => {
  const e = extOf(f).toLowerCase()
  if (e === 'pdf') return '#e53935'
  if (['doc', 'docx'].includes(e)) return '#1e6ee8'
  if (['xls', 'xlsx'].includes(e)) return '#1e9e57'
  return '#909399'
}

const contentUrl = (f) => attachmentContentUrl(f)

// ---- 点击行为：图片全屏预览，文件下载 ----
const viewerVisible = ref(false)
const viewerUrls = ref([])
const viewerIndex = ref(0)

const onOpen = (f) => {
  if (!contentUrl(f)) return
  if (isImage(f)) {
    // 同组图片共享预览列表，可左右切换
    const images = props.files.filter(isImage).map(contentUrl)
    viewerUrls.value = images
    viewerIndex.value = Math.max(0, images.indexOf(contentUrl(f)))
    viewerVisible.value = true
  } else {
    // 后端对非图片附件返回 Content-Disposition: attachment，浏览器自动下载到本地
    window.open(contentUrl(f), '_blank')
  }
}
</script>

<style scoped>
.att-grid { display: flex; flex-wrap: wrap; gap: 10px; }
.att-card {
  width: 110px; cursor: pointer; text-align: center; position: relative;
  border: 1px solid #e5e7eb; border-radius: 6px; padding: 6px;
  transition: border-color .15s, box-shadow .15s;
}
.att-card:hover { border-color: #409eff; box-shadow: 0 1px 6px rgba(64,158,255,.18); }
.att-thumb { width: 96px; height: 72px; object-fit: cover; border-radius: 4px; display: block; margin: 0 auto; }
.att-icon {
  height: 72px; display: flex; flex-direction: column; align-items: center; justify-content: center;
  gap: 2px; background: #f8fafc; border-radius: 4px;
}
.att-ext { font-size: 11px; font-weight: 600; color: #64748b; }
.att-name {
  font-size: 12px; margin-top: 4px; color: #475569;
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.att-remove {
  position: absolute; top: -6px; right: -6px;
  background: #f56c6c; color: #fff; border-radius: 50%;
  width: 18px; height: 18px; font-size: 12px;
  display: flex; align-items: center; justify-content: center;
  opacity: 0; transition: opacity .15s; cursor: pointer;
}
.att-card:hover .att-remove { opacity: 1; }
</style>
