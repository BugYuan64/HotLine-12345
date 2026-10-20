/**
 * 工单流转状态字典（统一入口，替代各页面分散维护的 statusLabels/statusType）
 * 同一状态码在管理端/部门端/街道办后端文案可能存在差异，用 role 区分。
 * role: 'ADMIN' 管理端 | 'DEPT' 部门端 | 'STREET' 街道办
 * label 为管理端默认文案；byRole 覆盖部门端(DEPT)/街道办(STREET)的差异化文案。
 * 显示规则（用户确认）：
 *   PENDING_DISPATCH       管理端=待分派 / 部门端+街道办=待管理端分派
 *   PENDING_DEPT_PROCESS   部门端=待处理 / 其他=待部门端处理
 *   PENDING_DEPT_ASSIGN    部门端=待分派 / 其他=待部门分派
 *   PENDING_STREET_PROCESS 街道办=待处理 / 其他=待街道办处理
 *   STREET_DONE            管理端=待部门端审核 / 部门端=待审核 / 街道办=已处理
 *   DEPT_AUDITED           管理端=待终审确认 / 部门端+街道办=已审核
 *   PENDING_REASSIGN       管理端=待重新分派
 */
export const ORDER_STATUS = {
  PENDING_DISPATCH:       { label: '待分派', color: 'warning',
                            byRole: { DEPT: { label: '待管理端分派' }, STREET: { label: '待管理端分派' } } },
  PENDING_UPDATE:         { label: '待更新', color: 'warning' },
  PENDING_DEPT_PROCESS:   { label: '待部门端处理', color: 'info',
                            byRole: { DEPT: { label: '待处理' } },
                            colorByRole: { DEPT: 'warning', STREET: 'warning' } },
  PENDING_DEPT_ASSIGN:    { label: '待部门分派', color: 'info',
                            byRole: { DEPT: { label: '待分派' } } },
  PENDING_STREET_PROCESS: { label: '待街道办处理', color: 'info',
                            byRole: { DEPT: { label: '待街道办处理' }, STREET: { label: '待处理' } } },
  STREET_DONE:            { label: '待部门端审核', color: 'primary',
                            byRole: { DEPT: { label: '待审核' }, STREET: { label: '已处理' } } },
  PENDING_EVALUATE:       { label: '待市民评价', color: 'warning',
                            byRole: { DEPT: { label: '待市民评价' }, STREET: { label: '已处理' } } },
  STREET_REJECTED:        { label: '街道办已驳回', color: 'danger',
                            byRole: { DEPT: { label: '街道办已驳回' }, STREET: { label: '已驳回' } } },
  DEPT_REJECTED:          { label: '部门已驳回', color: 'danger' },
  DEPT_AUDITED:           { label: '待终审确认', color: 'primary',
                            byRole: { DEPT: { label: '已审核' }, STREET: { label: '已审核' } } },
  PENDING_REASSIGN:       { label: '待重新分派', color: 'warning',
                            byRole: { DEPT: { label: '待管理端分派' }, STREET: { label: '待管理端分派' } } },
  CLOSED:                 { label: '已办结', color: 'success' },
  EVALUATED:              { label: '已评价', color: 'success',
                            byRole: { DEPT: { label: '已评价，待审核' } } },
  RETURNED:               { label: '已退回', color: 'danger' },
}

export function orderStatus(code, role = 'ADMIN') {
  const v = ORDER_STATUS[code]
  if (!v) return { label: code != null ? String(code) : '-', color: 'info' } // 未知码兜底：原样显示英文
  const label = v.byRole?.[role]?.label ?? v.label
  const color = v.colorByRole?.[role] ?? v.color
  return { label, color }
}

export const orderStatusLabel = (code, role) => orderStatus(code, role).label
export const orderStatusColor = (code, role) => orderStatus(code, role).color

export default ORDER_STATUS
