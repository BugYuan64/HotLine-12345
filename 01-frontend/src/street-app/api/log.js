import request from './request'

/** 街道办系统审计日志（本账号，/street/logs） */
export const getLogs = (params) =>
  request.get('/street/logs', { params })
