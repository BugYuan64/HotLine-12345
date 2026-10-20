import request from './request'

/** 本账号系统审计日志查询（后端强制 operator=当前账号，前端不可越权） */
export const getMyLogs = (params) =>
  request.get('/staff/logs', { params })
