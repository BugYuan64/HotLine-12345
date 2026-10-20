import request from './request'

/** 一级分类列表（分类下拉/展示数据源，/common 所有已登录角色可用） */
export const getPrimaries = () =>
  request.get('/common/classify/primaries')
