import request from './request'

/** 街道办权限内工单查询（本辖区，/street/** 独立后端） */
export const getOrders = (params) =>
  request.get('/street/orders', { params })

/** 街道办工单详情 */
export const getOrderDetail = (id) =>
  request.get(`/street/orders/${id}`)

/** 街道办处理工单（待街道办处理 → 街道办已处理） */
export const disposeOrder = (id, data) =>
  request.post(`/street/orders/${id}/dispose`, data)

/** 街道办驳回（固定原因） */
export const rejectOrder = (id, data) =>
  request.post(`/street/orders/${id}/reject`, data)

/** 街道办处置凭证附件上传 */
export const uploadAttachment = (id, formData) =>
  request.post(`/street/orders/${id}/attachment`, formData, { headers: { 'Content-Type': 'multipart/form-data' } })

/** 工单流转时间线 */
export const getTimeline = (id) =>
  request.get(`/street/orders/${id}/timeline`)

/** 街道办工作台统计 */
export const getWorkbench = () =>
  request.get('/street/workbench')
