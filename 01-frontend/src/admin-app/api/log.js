import request from './request'

/** 系统日志与 AI 生成内容审计查询（API 8.6，日志不可删除/修改） */
export const getLogs = (params) =>
  request.get('/admin/logs', { params })

/** 部门审计：所有部门端系统日志（可按部门代号筛选） */
export const getDeptLogs = (params) =>
  request.get('/admin/dept-logs', { params })

/** 街道办审计：所有街道办端系统日志（可按区县筛选） */
export const getStreetLogs = (params) =>
  request.get('/admin/street-logs', { params })
