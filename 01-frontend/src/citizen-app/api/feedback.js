import request from './request'

/** 处置结果查看（API 11.4.1；未处置完成时后端报错，页面需静默处理，可传 opts.skipErrorMessage 跳过错误提示）
 *  childAppealId：拆分后的子诉求编号，查看该子诉求对应工单的处置结果 */
export const getResult = (appealNo, childAppealId, phone, opts) =>
  request.get(`/public/appeal/${appealNo}/result`, { params: { phone, child_appeal_id: childAppealId || undefined }, ...opts })

/** 满意度评价（API 11.4.2）：data 中可携带 child_appeal_id 对拆分后的子诉求单独评价 */
export const submitEvaluate = (appealNo, data) =>
  request.post(`/public/appeal/${appealNo}/evaluate`, data)

/** 重开申请（API 11.4.3）：data 中可携带 child_appeal_id 对拆分后的子诉求单独重开 */
export const applyReopen = (appealNo, data) =>
  request.post(`/public/appeal/${appealNo}/reopen`, data)

/** 工单流转时间线（API 11.4.4）：拆分后返回 groups（父诉求 + 各子诉求分组） */
export const getFeedbackTimeline = (appealNo, phone) =>
  request.get(`/public/appeal/${appealNo}/timeline`, { params: { phone } })

/** 诉求级流程进度：管理端处理中 → 部门端处理中(具体部门) → 街道办处理中(具体街道办) → 已办结 */
export const getAppealStage = (appealNo, phone) =>
  request.get(`/public/appeal/${appealNo}/stage`, { params: { phone } })