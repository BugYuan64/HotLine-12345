/**
 * 列表"新"角标统一逻辑（前端会话态 + 快照判新，零落库）：
 * - 进入页面时取该模块游标快照（snapshot），本次会话所有 load 都用快照判新：
 *   create_time 晚于快照 → 行 is_new=true（只有新增数据触发）
 * - 点击行（查看/展开箭头/操作按钮）→ markRowSeen(row)：该行角标立即消失（seenIds 记忆，重载不回弹）
 * - 离开页面 → reportRead()：推进游标（本页角标全部覆灭 + 侧边栏徽章清零）
 * - 刷新页面 → 会话态天然重置
 *
 * @param {string} module        模块标识
 * @param {object} io            端相关 IO：{ getLastViewed(module), markRead(module) }
 * @param {string[]} [timeKeys]  行判新时间键（依次取第一个非空值，默认 ['create_time']——仅新增触发）
 */
import { ref } from 'vue'

export function useNewMarker(module, io, timeKeys = ['create_time']) {
  const snapshot = ref(null)      // 进入时的游标快照（ISO 串或 null）
  let ready = false               // 快照是否已取得
  let initPromise = null          // init 的 Promise（供页面在列表加载前等待，避免快照竞态）
  let pageRows = []               // 当前页行引用
  const seenIds = new Set()       // 本会话已操作过的行 id（消失后不回弹）

  const rowTime = (row) => {
    for (const k of timeKeys) {
      const v = row[k]
      if (v) return v
    }
    return null
  }

  /** 初始化：取得快照（进入页面时调用一次，可重复调用返回同一 Promise）。
   *  note: 若列表已被 decorate（如 AuditLogPanel 子组件先于父组件 load），快照就绪后对已缓存行重算 is_new，
   *  避免 init 竞态导致首屏"新"点丢失 */
  const init = () => {
    if (!initPromise) {
      initPromise = (async () => {
        try {
          snapshot.value = await io.getLastViewed(module)
        } catch { snapshot.value = null }
        ready = true
        if (pageRows.length) {
          pageRows.forEach(r => { r.is_new = isNew(r) })
        }
      })()
    }
    return initPromise
  }

  /** 等待快照就绪（decorate/reportRead 前调用，保证先取游标再标记/推进）；未 init 时自动补 init */
  const ensureReady = () => init()

  /** 行判新：create_time 晚于快照 → 新；已点过的行不标红 */
  const isNew = (row) => {
    if (row.id != null && seenIds.has(String(row.id))) return false
    if (!ready || !snapshot.value) return false
    const t = rowTime(row)
    return !!t && new Date(t).getTime() > new Date(snapshot.value).getTime()
  }

  /** load 返回后调用：逐行补算 is_new，并登记当前页行 */
  const decorate = (rows) => {
    pageRows = rows || []
    ;(rows || []).forEach(r => { r.is_new = isNew(r) })
  }

  /** 点击行（查看/展开箭头/操作按钮）后调用：该行角标立即消失 */
  const markRowSeen = (row) => {
    if (!row) return
    row.is_new = false
    if (row.id != null) seenIds.add(String(row.id))
  }

  /** 上报已读：推进后端游标并通知侧边栏刷新（徽章清零） */
  const reportRead = () => {
    return Promise.resolve(io.markRead(module)).finally(() =>
      window.dispatchEvent(new Event('unread:refresh')))
  }

  return { init, ensureReady, decorate, isNew, markRowSeen, reportRead, snapshot }
}
