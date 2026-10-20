import request from './request'

/**
 * 通知公告 API（公众端）
 * GET /public/notices?page=&size=    公告分页列表
 * GET /public/notices/top?limit=     首页顶部公告（默认3条）
 * GET /public/notices/{id}           公告详情
 */

/** 公告分页列表（keyword 标题/正文/摘要模糊搜索；后端参数名为 size） */
export const getNotices = (page = 1, size = 10, keyword = '') =>
  request.get('/public/notices', { params: { page, size, keyword: keyword || undefined } })

/** 首页顶部公告（默认3条） */
export const getNoticeTop = (limit = 3) =>
  request.get('/public/notices/top', { params: { limit } })

/** 公告详情 */
export const getNoticeDetail = (id) =>
  request.get(`/public/notices/${id}`)
