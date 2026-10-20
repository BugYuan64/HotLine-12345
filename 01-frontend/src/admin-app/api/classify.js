import request from './request'

/** 分类体系查询（需求 10.3(1)，分类树/下拉数据源） */
export const getCategories = () =>
  request.get('/admin/classify/categories')

/** 新增二级分类标签（需求 10.3(1)） */
export const addCategory = (data) =>
  request.post('/admin/classify/categories', data)

/** 一级分类列表（需求 10.3(1)） */
export const getPrimaries = () =>
  request.get('/admin/classify/primaries')

/** 新增一级分类（需求 10.3(1)） */
export const addPrimary = (data) =>
  request.post('/admin/classify/primaries', data)

/** 编辑一级分类（需求 10.3(1)） */
export const updatePrimary = (id, data) =>
  request.put('/admin/classify/primaries/' + id, data)

/** 删除一级分类（需求 10.3(1)） */
export const deletePrimary = (id) =>
  request.delete('/admin/classify/primaries/' + id)

/** 关键词分页查询 */
export const getKeywords = (params) =>
  request.get('/admin/classify/keywords', { params })

/** 新增关键词（API 7.1） */
export const addKeyword = (data) =>
  request.post('/admin/classify/keywords', data)

/** 编辑关键词（需求 10.3(2)） */
export const updateKeyword = (id, data) =>
  request.put(`/admin/classify/keywords/${id}`, data)

/** 删除关键词（需求 10.3(2)） */
export const deleteKeyword = (id) =>
  request.delete(`/admin/classify/keywords/${id}`)

/** 分类规则列表 */
export const getRules = () =>
  request.get('/admin/classify/rules')

/** 分类规则配置（API 7.2，已存在则更新） */
export const saveRule = (data) =>
  request.post('/admin/classify/rules', data)

/** RAG 样本分页查询 */
export const getSamples = (params) =>
  request.get('/admin/classify/samples', { params })

/** 新增样本（API 7.3） */
export const addSample = (data) =>
  request.post('/admin/classify/samples', data)

/** 编辑/停用样本（API 7.3 PUT） */
export const updateSample = (id, data) =>
  request.put(`/admin/classify/samples/${id}`, data)

/** 删除样本（API 7.3 DELETE） */
export const deleteSample = (id) =>
  request.delete(`/admin/classify/samples/${id}`)

/** 分类效果测试（API 7.4） */
/** 重建分类知识桥（cls:* Redis 桥全量同步，数据库直插/重置后使用） */
export const rebuildBridge = () =>
  request.post('/admin/classify/rebuild-bridge')

export const testClassify = (data) =>
  request.post('/admin/classify/test', data)

/** 规则与样本 Excel 批量导入（API 7.5） */
export const importClassify = (formData) =>
  request.post('/admin/classify/import', formData, { headers: { 'Content-Type': 'multipart/form-data' } })

/** 规则与样本 Excel 导出（API 7.6，流式下载 blob 响应） */
export const exportClassify = (type) =>
  request.get('/admin/classify/export', { params: { type }, responseType: 'blob' })

/** 分类准确率统计（API 7.7） */
export const getClassifyAccuracy = (params) =>
  request.get('/admin/classify/accuracy', { params })
