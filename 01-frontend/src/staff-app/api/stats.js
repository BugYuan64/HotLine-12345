import request from './request'

/** 部门端诉求趋势折线（工作台）：一级分类由后端按登录部门 user_type 强制锁定，
 *  仅二级分类（含空=一级总括）、区县（前缀匹配只到区级）、近 N 天可筛 */
export const getStaffDailyTrend = (params) =>
  request.get('/staff/trends/daily', { params })
