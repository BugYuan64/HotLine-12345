import request from './request'

/**
 * 通知公告管理 API（管理端，通知公告模块）：
 * GET  /admin/notices/options          发布弹窗下拉数据（八大部门 + 全部街道办）
 * GET  /admin/notices                  分页列表（发布者/日期/标题/状态筛选）
 * GET  /admin/notices/{id}             详情（含接收对象明细，编辑回填）
 * POST /admin/notices                  发布（发布者可选管理员/八大部门名义，接收对象多选）
 * PUT  /admin/notices/{id}             编辑（名义发布者不可改）
 * DELETE /admin/notices/{id}           下架（status=0，不物理删除）
 */

/** 发布弹窗下拉数据（八大部门 + 全部街道办） */
export const getNoticeOptions = () =>
  request.get('/admin/notices/options')

/** 通知分页列表 */
export const getNotices = (params) =>
  request.get('/admin/notices', { params })

/** 通知详情（含接收对象明细） */
export const getNoticeDetail = (id) =>
  request.get(`/admin/notices/${id}`)

/** 发布通知 */
export const createNotice = (data) =>
  request.post('/admin/notices', data)

/** 编辑通知（名义发布者不可改） */
export const updateNotice = (id, data) =>
  request.put(`/admin/notices/${id}`, data)

/** 下架通知 */
export const offShelfNotice = (id) =>
  request.delete(`/admin/notices/${id}`)

/** 重新发布已下架通知（可改内容/接收对象；发布时间刷新，清空已读全员重新提醒） */
export const republishNotice = (id, data) =>
  request.put(`/admin/notices/${id}/republish`, data)
