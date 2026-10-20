import request from './request'

/** 查询全部区县（预警/预判区县下拉，复用公众端区划接口） */
export const getCounties = () =>
  request.get('/public/regions/counties')

/** 预警规则列表（API 8.1 GET） */
export const getWarningRules = () =>
  request.get('/admin/warnings/rules')

/** 新增预警规则（API 8.1 POST） */
export const addWarningRule = (data) =>
  request.post('/admin/warnings/rules', data)

/** 编辑/启停预警规则（API 8.1 PUT） */
export const updateWarningRule = (id, data) =>
  request.put(`/admin/warnings/rules/${id}`, data)

/** 删除预警规则（API 8.1 DELETE） */
export const deleteWarningRule = (id) =>
  request.delete(`/admin/warnings/rules/${id}`)

/** 二级分类建议列表（API 8.1）：按一级分类返回已用于工单的二级分类 */
export const getSubCategories = (category) =>
  request.get('/admin/warnings/rules/sub-categories', { params: { category } })

/** 标记预警已响应（需求 11(4)） */
export const respondWarning = (id) =>
  request.put(`/admin/warnings/${id}/respond`)

/** 预警分发进度（只读）：已分发街道办及各自响应状态 */
export const getWarningDispatches = (id) =>
  request.get(`/admin/warnings/${id}/dispatches`)

/** 审核发送预警（方案B：待审核 → 已发送，推送站内消息） */
export const sendWarning = (id) =>
  request.put(`/admin/warnings/${id}/send`)

/** 审核驳回预警（方案B：待审核 → 已驳回，保留备查） */
export const rejectWarning = (id) =>
  request.put(`/admin/warnings/${id}/reject`)

/** 审核中编辑 AI 总结与应对措施（方案B） */
export const updateWarningContent = (id, data) =>
  request.put(`/admin/warnings/${id}/content`, data)

/** 从预警记录生成预警规则草稿（方案B） */
export const generateRuleFromRecord = (id) =>
  request.post(`/admin/warnings/${id}/generate-rule`)

/** 手动立即扫描预警（A5）：即时触发规则扫描（AI 总结耗时较长，单独放大超时避免前端误报错） */
export const scanWarnings = () =>
  request.post('/admin/warnings/scan', null, { timeout: 180000 })

/** 预警记录查询（API 8.2） */
export const getWarnings = (params) =>
  request.get('/admin/warnings', { params })

/** 优秀处置案例分页查询（API 10.12，预警建议 RAG 检索来源） */
export const getWarningCases = (params) =>
  request.get('/admin/warnings/cases', { params })

/** 新增优秀处置案例（API 10.12 POST） */
export const addWarningCase = (data) =>
  request.post('/admin/warnings/cases', data)

/** 编辑优秀处置案例（API 10.12 PUT） */
export const updateWarningCase = (id, data) =>
  request.put(`/admin/warnings/cases/${id}`, data)

/** 删除优秀处置案例（API 10.12 DELETE） */
export const deleteWarningCase = (id) =>
  request.delete(`/admin/warnings/cases/${id}`)

/** 优秀处置案例 Excel 批量导入（API 10.12） */
export const importWarningCases = (formData) =>
  request.post('/admin/warnings/cases/import', formData, { headers: { 'Content-Type': 'multipart/form-data' } })

/** 敏感事件查询（P0-3 / 需求 9(9)） */
export const getSensitiveEvents = (params) =>
  request.get('/admin/warnings/sensitive-events', { params })

/** 标记敏感事件已处理（P0-3） */
export const handleSensitiveEvent = (id) =>
  request.put(`/admin/warnings/sensitive-events/${id}/handle`)

/** 预判动态下钻（预警管理融合页）：按区县/一级/二级/周期筛选最近一批预判结果并分组 */
export const getPredictionQuery = (params) =>
  request.get('/admin/predictions/query', { params })

/** 预判参数配置回显（API 8.8） */
export const getPredictionConfig = () =>
  request.get('/admin/prediction/config')

/** 更新预判参数配置（API 8.8） */
export const updatePredictionConfig = (data) =>
  request.put('/admin/prediction/config', data)

/** 预判结果一键生成预警规则草稿（DRAFT，需人工确认启用） */
export const createRuleFromPrediction = (data) =>
  request.post('/admin/warnings/rules/from-prediction', data)

/** 手动重新预判（无需重启后端）：触发一次预判，返回 { hotspots, degraded }（AI 耗时较长，单独放大超时避免前端误报错） */
export const runPrediction = () =>
  request.post('/admin/predictions/run', null, { timeout: 180000 })
