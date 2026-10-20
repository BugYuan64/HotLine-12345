import request from './request'

/** 管理端待办聚合（"待我介入"卡片数据源） */
export const getTodoSummary = () =>
  request.get('/admin/todo/summary')