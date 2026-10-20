/**
 * 用户类型字典
 */
export const USER_TYPE = {
  STREET:    { label: '街道办', color: 'success' },
  URBAN:     { label: '城管', color: 'primary' },
  PROPERTY:  { label: '物业', color: 'warning' },
  LIVELIHOOD: { label: '民生', color: 'info' },
  ADMIN:     { label: '管理员', color: 'danger' },
}

export const userTypeLabel = (t) => USER_TYPE[t]?.label || t
export const userTypeColor = (t) => USER_TYPE[t]?.color || 'info'

export default USER_TYPE