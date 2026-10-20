import { nextTick } from 'vue'

/**
 * 四端 Layout 主内容滚动容器统一为 <main class="content">。
 * 分页/刷新类数据替换会引发瞬时高度变化，被浏览器钳制导致滚动条跳顶；
 * 配合 captureContentScroll/restoreContentScroll 在数据加载前后保持滚动位置。
 */
const scroller = () => document.querySelector('main.content')

/** 记录当前滚动位置（分页/刷新加载前调用） */
export const captureContentScroll = () => {
  const el = scroller()
  return el ? el.scrollTop : null
}

/** 数据替换完成后恢复滚动位置（nextTick 后回写；越界由浏览器钳制，安全） */
export async function restoreContentScroll(y) {
  await nextTick()
  const el = scroller()
  if (el && y != null) el.scrollTop = y
}
