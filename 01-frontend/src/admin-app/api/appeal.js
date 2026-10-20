import request from './request'

/** 诉求查询与追溯（API 6.1） */
export const getAppeals = (params) =>
  request.get('/admin/appeals', { params })

/** 新增诉求 */
export const createAppeal = (data) =>
  request.post('/admin/appeals', data)

/** 编辑诉求（scope=parent/child 区分父/子诉求） */
export const updateAppeal = (id, scope, data) =>
  request.put(`/admin/appeals/${id}`, data, { params: { scope } })

/** 删除诉求（scope=parent/child 区分父/子诉求） */
export const deleteAppeal = (id, scope) =>
  request.delete(`/admin/appeals/${id}`, { params: { scope } })

/** 查询某父诉求的全部子诉求 */
export const getChildren = (parentId) =>
  request.get('/admin/appeals/' + parentId + '/children')

/** 诉求附件列表（市民提交时上传，MinIO 预签名下载链接）；scope=parent/child */
export const getAppealAttachments = (id, scope = 'parent') =>
  request.get(`/admin/appeals/${id}/attachments`, { params: { scope } })

/** 查询子诉求对应工单详情（查看工单弹窗） */
export const getChildOrder = (childId) =>
  request.get('/admin/appeals/' + childId + '/order')

/** 手动触发诉求自动处理（拆分 + 建单） */
export const triggerAutoProcess = () =>
  request.post('/admin/appeals/auto-process')

/** 按勾选父诉求ID手动拆分（异步，返回 taskId） */
export const splitAppeals = (parentIds) =>
  request.post('/admin/appeals/split', parentIds)

/** 查询拆分任务进度 */
export const getSplitTask = (taskId) =>
  request.get('/admin/appeals/split-task/' + taskId)

/** 撤回父诉求拆分：删除子诉求，父诉求回退 PENDING_AI */
export const withdrawSplit = (parentId) =>
  request.post('/admin/appeals/' + parentId + '/withdraw-split')

/** 按勾选子诉求ID生成工单（异步，返回 taskId） */
export const createOrders = (childIds) =>
  request.post('/admin/appeals/create-orders', childIds)

/** 查询生成工单任务进度 */
export const getCreateOrderTask = (taskId) =>
  request.get('/admin/appeals/create-orders/task/' + taskId)

/** 获取自动/手动审批配置 */
export const getAppealConfig = () =>
  request.get('/admin/appeals/config')

/** 设置自动/手动审批配置 */
export const setAppealConfig = (data) =>
  request.put('/admin/appeals/config', data)

// —— 自动模式（全量拆分/建单 + 可取消 + 进度追踪） ——
export const startAutoSplit = () => request.post('/admin/appeals/auto-split')
export const getAutoSplitStatus = () => request.get('/admin/appeals/auto-split/status')
export const cancelAutoSplit = () => request.post('/admin/appeals/auto-split/cancel')
export const startAutoCreateOrders = () => request.post('/admin/appeals/auto-create-orders')
export const getAutoCreateOrdersStatus = () => request.get('/admin/appeals/auto-create-orders/status')
export const cancelAutoCreateOrders = () => request.post('/admin/appeals/auto-create-orders/cancel')