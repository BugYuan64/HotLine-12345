/**
 * 诉求状态字典（父诉求/子诉求共用，语义一致）
 */
export const APPEAL_STATUS = {
  TITLE_PENDING:      { label: '待AI生成标题', color: 'info' },
  TITLE_GENERATING:   { label: 'AI标题生成中', color: 'warning' },
  TITLE_GENERATED:    { label: '待拆分', color: 'info' },
  SPLITTING:          { label: 'AI拆分中', color: 'warning' },
  CHILDREN_GENERATED: { label: '拆分完成', color: 'success' },
  AUDIT_PENDING:      { label: '待AI审核', color: 'info' },
  AUDITING:           { label: 'AI审核中', color: 'warning' },
  CORRECTING:         { label: 'AI类别纠正中', color: 'warning' },
  CLASSIFYING:        { label: 'AI一级分类', color: 'warning' },
  CLASSIFYING_SUB:    { label: 'AI二级分类', color: 'warning' },
  READY:              { label: '待生成工单', color: 'primary' },
  ORDERING:           { label: '生成工单中', color: 'warning' },
  ORDERED:            { label: '已生成工单', color: 'success' },
  WITHDRAWN:          { label: '已撤回', color: 'danger' },
}

export const appealStatusLabel = (s) => APPEAL_STATUS[s]?.label || s
export const appealStatusColor = (s) => APPEAL_STATUS[s]?.color || 'info'

/** 诉求类别（8大类 code + OTHER），与 classify_primary 对齐 */
export const APPEAL_CATEGORY = {
  URBAN: '城市管理', TRAFFIC: '交通管理', MARKET: '市场监管', ENVIRONMENT: '环境保护',
  HOUSING: '住房保障', LABOR: '劳动保障', PUBLIC: '公共服务', HEALTH: '医疗卫生', OTHER: '其他',
  // 旧 3 类时期历史遗留分类（仅历史数据显示用，新诉求不再产生）
  PROPERTY: '物业', LIVELIHOOD: '民生'
}
export const appealCategoryLabel = (c) => APPEAL_CATEGORY[c] || c || '-'

export default APPEAL_STATUS