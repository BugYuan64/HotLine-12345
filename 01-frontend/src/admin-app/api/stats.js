import request from './request'

/** 全局数据统计看板（API 8.4） */
export const getStatsSummary = (params) =>
  request.get('/admin/stats/summary', { params })

/** 统计报表导出（API 8.5，流式下载 Excel，blob 响应） */
export const exportStatsReport = (params) =>
  request.get('/admin/stats/report', { params, responseType: 'blob' })

/** 诉求趋势折线（预警与预判页「诉求趋势对比」）：一级/二级分类 + 区县 + 近 N 天可筛 */
export const getAdminDailyTrend = (params) =>
  request.get('/admin/trends/daily', { params })
