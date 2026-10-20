import request from './request'

/** 查询全部区县（手动录入工单区县下拉，复用公众端区划接口） */
export const getCounties = () =>
  request.get('/public/regions/counties')

/** 查询某区县下的街道/镇/乡（手动录入工单乡镇级下拉） */
export const getRegionChildren = (parent) =>
  request.get('/public/regions/children', { params: { parent } })

/** 工单查询（API 6.2） */
export const getOrders = (params) =>
  request.get('/admin/orders', { params })

/** 待确认工单审核确认（API 6.3） */
export const confirmOrder = (id) =>
  request.post(`/admin/orders/${id}/confirm`)

/** 取消确认：待分派回退待确认 */
export const cancelConfirmOrder = (id) =>
  request.post(`/admin/orders/${id}/cancel-confirm`)

/** 待更新工单等级升级确认（API 6.4） */
export const upgradeOrder = (id, data) =>
  request.post(`/admin/orders/${id}/upgrade`, data)

/** 手动录入工单（API 6.5） */
export const createOrder = (data) =>
  request.post('/admin/orders', data)

/** 编辑工单（API 6.6） */
export const updateOrder = (id, data) =>
  request.put(`/admin/orders/${id}`, data)

/** 删除工单（API 6.7） */
export const deleteOrder = (id) =>
  request.delete(`/admin/orders/${id}`)

/** 工单 Excel 批量导入（API 6.8） */
export const importOrders = (formData) =>
  request.post('/admin/orders/import', formData, { headers: { 'Content-Type': 'multipart/form-data' } })

/** 批量导出工单（API 6.8 对称）：按勾选 id 流式下载 Excel（blob，前 4 列对齐导入模板可回导） */
export const exportOrders = (ids) =>
  request.post('/admin/orders/export', { ids }, { responseType: 'blob' })

/** 工单分类人工修正（API 6.9） */
export const correctOrderCategory = (id, data) =>
  request.post(`/admin/orders/${id}/classify-correct`, data)

/** 管理员手动分派/转派（API 6.10） */
export const dispatchOrder = (id, data) =>
  request.post(`/admin/orders/${id}/dispatch`, data)

/** 自动派单开关配置读取 */
export const getAutoDispatchConfig = () =>
  request.get('/admin/orders/auto-dispatch/config')

/** 自动派单开关设置（true=自动派单 / false=手动派单） */
export const setAutoDispatchConfig = (autoDispatch) =>
  request.put('/admin/orders/auto-dispatch/config', { autoDispatch })

/** 立即启动一轮自动派单（手动触发） */
export const startAutoDispatch = () =>
  request.post('/admin/orders/auto-dispatch')

/** 自动派单进度查询（开关状态 + 任务进度 + 待分配数） */
export const getAutoDispatchStatus = () =>
  request.get('/admin/orders/auto-dispatch/status')

/** 取消自动派单 */
export const cancelAutoDispatch = () =>
  request.post('/admin/orders/auto-dispatch/cancel')

/** 待终审工单列表（API 11.3.1） */
export const getFinalAuditing = (params) =>
  request.get('/admin/orders/final-auditing', { params })

/** 全链路信息查看（API 11.3.2） */
export const getFullChain = (id) =>
  request.get('/admin/orders/' + id + '/full-chain')

/** 工单详情（含合并子诉求，工单分页「查看」弹窗用） */
export const getOrderDetail = (id) =>
  request.get('/admin/orders/' + id + '/detail')

/** 工单流转时间线（工单管理行展开用） */
export const getOrderTimeline = (id) =>
  request.get('/admin/orders/' + id + '/timeline')

/** 终审确认（新流程：已办结工单 → 确认归档/退回） */
export const finalConfirm = (id, data) =>
  request.post('/admin/orders/' + id + '/final-confirm', data)

/** @deprecated 老版终审流程（现走 finalConfirm）——页面已不调用，保留仅为兼容 */
export const finalApprove = (id) =>
  request.post(`/admin/orders/${id}/final-approve`)

/** @deprecated 老版终审流程（现走 finalConfirm） */
export const finalReject = (id, data) =>
  request.post(`/admin/orders/${id}/final-reject`, data)

/** @deprecated 老版终审流程（现走 finalConfirm） */
export const batchFinalAudit = (data) =>
  request.post('/admin/orders/batch-final-audit', data)

/** 手动归档（API 11.3.6） */
export const archiveOrder = (id) =>
  request.post(`/admin/orders/${id}/archive`)

/** 终审重新分派：部门已驳回 → 待管理端分派 */
export const reassignOrder = (id) =>
  request.post(`/admin/orders/${id}/reassign`)

/** 重新分类：不传 data=打开弹窗留痕；传 data=保存新分类并退回「待分派」（分派由「分派」按钮完成） */
export const reclassifyOrder = (id, data) =>
  request.post(`/admin/orders/${id}/reclassify`, data || {})

/** 归档数据管理（API 11.3.7） */
export const getArchives = (params) =>
  request.get('/admin/orders/archives', { params })

/** 重开申请列表（API 11.3.8） */
export const getReopenApplications = (params) =>
  request.get('/admin/orders/reopen-applications', { params })

/** 重开申请审批（API 11.3.8） */
export const reopenApprove = (id, data) =>
  request.post(`/admin/orders/${id}/reopen-approve`, data)
