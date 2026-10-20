/**
 * 统一删除/危险操作确认（ElMessageBox 包装：统一风险色与按钮文案）
 * 返回 true=确认执行；false=取消
 */
import { ElMessageBox } from 'element-plus'

export const confirmDelete = async (message = '确定删除该条数据？删除后不可恢复') => {
  try {
    await ElMessageBox.confirm(message, '删除确认', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning'
    })
    return true
  } catch {
    return false
  }
}

export const confirmAction = async (message, title = '操作确认', confirmText = '确定') => {
  try {
    await ElMessageBox.confirm(message, title, {
      confirmButtonText: confirmText,
      cancelButtonText: '取消',
      type: 'warning'
    })
    return true
  } catch {
    return false
  }
}
