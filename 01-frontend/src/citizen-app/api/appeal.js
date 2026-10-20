import request from './request'

/** 提交诉求（API 4.1） */
export const submitAppeal = (data) =>
  request.post('/public/appeal', data)

/** 智能诉求引导与补全建议（API 4.2；静默失败不打扰填写） */
export const getSuggest = (partialContent) =>
  request.post('/public/appeal/suggest', { partial_content: partialContent }, { skipErrorMessage: true })

/**
 * 诉求附件上传（API 12.1 通用上传）：后端无 /public/appeal/attachment 端点，
 * 改走跨角色通用上传 /common/upload（biz_type=appeal_attachment），返回 file_id 供提交诉求引用
 */
export const uploadAttachment = (file, bizType) => {
  const formData = new FormData()
  formData.append('file', file)
  formData.append('biz_type', bizType)
  return request.post('/common/upload', formData, {
    headers: { 'Content-Type': 'multipart/form-data' }
  })
}

/** 查询全部区县（市民投诉区县下拉） */
export const getCounties = () =>
  request.get('/public/regions/counties')

/** 查询某区县下的街道/镇/乡（市民投诉乡镇级下拉） */
export const getRegionChildren = (parent) =>
  request.get('/public/regions/children', { params: { parent } })

/** 当前账号诉求列表（分页，后端参数名为 size）：首页"办理进度" / "查看全部诉求"；appealNo 可空，为空查全部 */
export const listMyAppeals = (page = 1, size = 10, appealNo) => {
  const params = { page, size }
  if (appealNo) params.appealNo = appealNo
  return request.get('/public/appeal/list', { params })
}

/** 删除诉求（软删除）：仅已评价或街道办处置满 7 天未评价（超评价窗口）的诉求可删；删除后仅系统留档，列表不再展示 */
export const deleteAppeal = (appealNo) =>
  request.delete(`/public/appeal/${appealNo}`)

/** 当前账号敏感事件列表（市民端"办理进度"：敏感剥离诉求快照的进度查看）；appealNo 可空 */
export const listMySensitiveEvents = (page = 1, size = 10, appealNo) => {
  const params = { page, size }
  if (appealNo) params.appealNo = appealNo
  return request.get('/public/appeal/sensitive/list', { params })
}

/** 一级分类/部门映射（/common 下所有已登录角色可用）：市民端据此把部门代码（URBAN）翻译成中文部门名（城管部门） */
export const getPrimaries = () =>
  request.get('/common/classify/primaries', { skipErrorMessage: true })

// 注：处置结果/满意度评价/重开申请/流转时间线统一从 api/feedback.js 导入（API 11.4.1~11.4.4）
