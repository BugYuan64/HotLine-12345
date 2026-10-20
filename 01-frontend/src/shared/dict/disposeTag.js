/**
 * 街道办处置标签字典
 */
export const DISPOSE_TAG = {
  RESOLVED:      { label: '已解决', color: 'success' },
  PART_RESOLVED: { label: '部分解决', color: 'warning' },
  UNRESOLVED:    { label: '无法解决', color: 'danger' },
  TRANSFERRED:   { label: '转其他部门', color: 'info' },
}

export const disposeTagLabel = (t) => DISPOSE_TAG[t]?.label || t
export const disposeTagColor = (t) => DISPOSE_TAG[t]?.color || 'info'

export default DISPOSE_TAG