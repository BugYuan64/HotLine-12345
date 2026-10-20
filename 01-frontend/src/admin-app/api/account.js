import request from './request'

/** 账号审核列表（API 5.3） */
export const getAudits = (params) =>
  request.get('/admin/audits', { params })

/** 账号审核通过（API 5.4） */
export const approveAudit = (id) =>
  request.post(`/admin/audits/${id}/approve`)

/** 账号审核驳回（API 5.5） */
export const rejectAudit = (id, reject_reason) =>
  request.post(`/admin/audits/${id}/reject`, { reject_reason })

/** 账号批量审核（API 5.7） */
export const batchAudit = (data) =>
  request.post('/admin/audits/batch', data)