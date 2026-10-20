import request from './request'

/**
 * 通知公告 API（街道办端，通知公告模块）：
 * GET   /street/notices            收到的通知（发布者/日期筛选，含已读标记）
 * GET   /street/notices/published  我发布的（本街道名义，仅市民公开）
 * POST  /street/notices            发布（层级限制：仅市民公开 PUBLIC）
 * PUT   /street/notices/{id}       编辑
 * DELETE /street/notices/{id}      下架
 * PUT   /street/notices/{id}/republish 重新发布（重建收件箱，已读清零）
 * GET   /street/notices/{id}       详情（点开自动记已读，红点减一）
 */

/** 收到的通知列表 */
export const getReceivedNotices = (params) =>
  request.get('/street/notices', { params })

/** 我发布的（本街道名义，仅市民公开） */
export const getPublishedNotices = (params) =>
  request.get('/street/notices/published', { params })

/** 发布公告（仅市民公开，后端强制 PUBLIC） */
export const createNotice = (data) =>
  request.post('/street/notices', data)

/** 编辑公告 */
export const updateNotice = (id, data) =>
  request.put(`/street/notices/${id}`, data)

/** 下架公告 */
export const offShelfNotice = (id) =>
  request.delete(`/street/notices/${id}`)

/** 重新发布 */
export const republishNotice = (id, data) =>
  request.put(`/street/notices/${id}/republish`, data)

/** 通知详情（点开自动记已读，红点减一） */
export const getNoticeDetail = (id) =>
  request.get(`/street/notices/${id}`)
