import request from './request'

/** 区县列表（公众端区划接口，免登录；部门端辖区筛选用） */
export const getCounties = () => request.get('/public/regions/counties')

/** 街道/镇/乡列表（按区县编码） */
export const getRegionChildren = (countyCode) =>
  request.get('/public/regions/children', { params: { parent: countyCode } })
