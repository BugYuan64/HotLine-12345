import request from './request'

/**
 * 通知公告 API（部门端，通知公告模块）：
 * GET  /staff/notices/received       Tab1 收到的通知（发布者/日期筛选，含逐条已读标记）
 * GET  /staff/notices/published      Tab2 我发布的（本部门名义全部通知，含管理员代发）
 * GET  /staff/notices/{id}           详情（点开自动记已读，红点减一）
 * POST /staff/notices                发布（仅街道办+市民公开，名义固定本部门）
 * PUT  /staff/notices/{id}           编辑（仅本部门名下；接收对象可改）
 * DELETE /staff/notices/{id}         下架（status=0，不物理删除）
 */

/** Tab1：收到的通知（发给我部门的展示中通知，含逐条已读标记） */
export const getReceivedNotices = (params) =>
  request.get('/staff/notices/received', { params })

/** Tab2：我发布的（本部门名义全部通知，含管理员代发） */
export const getPublishedNotices = (params) =>
  request.get('/staff/notices/published', { params })

/** 通知详情（点开自动记已读，红点减一） */
export const getNoticeDetail = (id) =>
  request.get(`/staff/notices/${id}`)

/** 发布通知（仅街道办+市民公开，名义固定本部门） */
export const createNotice = (data) =>
  request.post('/staff/notices', data)

/** 编辑通知（仅本部门名下；名义发布者不可改） */
export const updateNotice = (id, data) =>
  request.put(`/staff/notices/${id}`, data)

/** 下架通知（status=0，不物理删除） */
export const offShelfNotice = (id) =>
  request.delete(`/staff/notices/${id}`)

/** 重新发布已下架通知（可改内容/接收对象；发布时间刷新，清空已读全员重新提醒） */
export const republishNotice = (id, data) =>
  request.put(`/staff/notices/${id}/republish`, data)
