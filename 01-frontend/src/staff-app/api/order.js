import request from './request'

/** 权限内工单查询（API 9.5） */
export const getOrders = (params) =>
  request.get('/staff/orders', { params })

/** 权限内工单详情（API 9.12） */
export const getOrderDetail = (id) =>
  request.get(`/staff/orders/${id}`)

/** 部门筛选核验（API 9.2） */
export const verifyOrder = (id, data) =>
  request.post(`/staff/orders/${id}/verify`, data)

/** 分派至街道办（API 9.3） */
export const assignStreet = (id, data) =>
  request.post(`/staff/orders/${id}/assign-street`, data || {})

/** 分派下拉候选街道办（API 9.3 弹窗） */
export const getStreetOptions = (id) =>
  request.get(`/staff/orders/${id}/street-options`)

/** 部门端详情弹窗修改承办街道办（只改街道办，不改变流转状态） */
export const updateStreetOffice = (id, data) =>
  request.post(`/staff/orders/${id}/street-office`, data)

/** 工单流转时间线（API 11.1.5） */
export const getTimeline = (id) =>
  request.get(`/staff/orders/${id}/timeline`)

/** 部门端确认（待审核 → 待分派） */
export const confirmOrder = (id) =>
  request.post(`/staff/orders/${id}/confirm`)

/** 部门同意驳回：街道办已驳回 → 部门已驳回 */
export const deptReject = (id, data) =>
  request.post(`/staff/orders/${id}/dept-reject`, data)

/** 部门重新分派：街道办已驳回 → 待分派 */
export const deptReassign = (id) =>
  request.post(`/staff/orders/${id}/dept-reassign`)

/** 部门端申请驳回：待部门端处理 → 待管理端分派 */
export const applyReject = (id, data) =>
  request.post(`/staff/orders/${id}/apply-reject`, data)

/** 部门审核通过（API 11.2.2） */
export const auditApprove = (id) =>
  request.post(`/staff/orders/${id}/audit-approve`)

/** 部门审核退回（API 11.2.3） */
export const auditReject = (id, data) =>
  request.post(`/staff/orders/${id}/audit-reject`, data)

/** 批量审核（API 11.2.4） */
export const batchAudit = (data) =>
  request.post('/staff/orders/batch-audit', data)

/** 处置质量评分（API 11.2.5） */
export const scoreOrder = (id, data) =>
  request.post(`/staff/orders/${id}/score`, data)

/** 待审批延期申请列表（API 11.2.6） */
export const getDelayApplyList = () =>
  request.get('/staff/delay-apply')

/** 延期审批（API 11.2.6） */
export const approveDelay = (id, data) =>
  request.post(`/staff/delay-apply/${id}/approve`, data)

/** 部门汇总上报（API 11.2.7） */
export const getOrderReport = (params) =>
  request.get('/staff/order-report', { params })
