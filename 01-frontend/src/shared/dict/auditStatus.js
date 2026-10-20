/**
 * 账号审核状态字典
 */
export const AUDIT_STATUS = {
  PENDING:  { label: '待审核', color: 'warning' },
  APPROVED: { label: '通过', color: 'success' },
  REJECTED: { label: '驳回', color: 'danger' },
}

export const auditStatusLabel = (s) => AUDIT_STATUS[s]?.label || s
export const auditStatusColor = (s) => AUDIT_STATUS[s]?.color || 'info'

export default AUDIT_STATUS