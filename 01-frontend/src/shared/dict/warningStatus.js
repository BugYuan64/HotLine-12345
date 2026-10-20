/**
 * 预警相关状态字典
 * WARNING_RECORD_STATUS：预警记录审核状态（方案B状态机）
 * RESPOND_STATUS：部门响应状态
 */
export const WARNING_RECORD_STATUS = {
  PENDING:  { label: '待审核', color: 'warning' },
  SENT:     { label: '已发送', color: 'success' },
  REJECTED: { label: '已驳回', color: 'danger' },
}

export const RESPOND_STATUS = {
  UNRESPONDED: { label: '未响应', color: 'warning' },
  RESPONDED:   { label: '已响应', color: 'success' },
}

export const warningRecordStatusLabel = (s) => WARNING_RECORD_STATUS[s]?.label || s
export const warningRecordStatusColor = (s) => WARNING_RECORD_STATUS[s]?.color || 'info'
export const respondStatusLabel = (s) => RESPOND_STATUS[s]?.label || s
export const respondStatusColor = (s) => RESPOND_STATUS[s]?.color || 'info'

export default { WARNING_RECORD_STATUS, RESPOND_STATUS }