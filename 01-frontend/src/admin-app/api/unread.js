import request from './request'

/** 侧边菜单未读红点：当前用户各模块未读数汇总（API 未读红点） */
export const getUnreadSummary = () => request.get('/unread/summary')

/** 上报某模块已读清零：进入该模块页面时调用 */
export const markModuleRead = (module) => request.post('/unread/mark-read', { module })

/** 查询某模块当前游标（前端快照判新用）：返回 ISO 时间串或 null */
export const getLastViewed = (module) =>
  request.get('/unread/last-viewed', { params: { module } })
