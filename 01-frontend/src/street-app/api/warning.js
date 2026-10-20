import request from './request'

/** 街道办预警记录分页（/street/warnings） */
export const getWarnings = (params) =>
  request.get('/street/warnings', { params })

/** 标记街道办预警已响应 */
export const respondWarning = (id) =>
  request.put(`/street/warnings/${id}/respond`)

/** 工作台待响应预警数（分发给本街道且未响应） */
export const getUnrespondedCount = () =>
  request.get('/street/warnings/unresponded-count')
