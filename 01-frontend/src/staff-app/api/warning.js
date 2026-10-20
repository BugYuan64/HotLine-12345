import request from './request'

/** 本部门预警记录分页查询（部门端局部预警，只读，后端按 user_type 过滤数据范围） */
export const getWarnings = (params) =>
  request.get('/staff/warnings', { params })

/** 二级分类建议列表（部门端下拉筛选数据源）：按一级分类返回已用于工单的二级分类 */
export const getSubCategories = (category) =>
  request.get('/staff/warnings/sub-categories', { params: { category } })

/** 标记本部门预警已响应（关闭响应闭环） */
export const respondWarning = (id) =>
  request.put(`/staff/warnings/${id}/respond`)

/** 可分发街道候选（按区县分组；辖区为空列全市） */
export const getDispatchStreets = (id) =>
  request.get(`/staff/warnings/${id}/streets`)

/** 分发预警到选中的街道办（street_offices: 街道名数组） */
export const dispatchWarning = (id, data) =>
  request.post(`/staff/warnings/${id}/dispatch`, data)

/** 本部门查看预警分发进度 */
export const getDispatchProgress = (id) =>
  request.get(`/staff/warnings/${id}/dispatches`)

/** 工作台待跟进预警数（已发送给本部门且响应闭环未完成） */
export const getUnrespondedCount = () =>
  request.get('/staff/warnings/unresponded-count')