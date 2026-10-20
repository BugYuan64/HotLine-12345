/**
 * 通用格式化工具（四端共享）
 */

/** 时间格式化：'yyyy-MM-dd HH:mm:ss'；空值返回 def（默认空串），非法值原样返回 */
export const formatTime = (val, def = '') => {
  if (!val) return def
  try {
    const d = new Date(val)
    if (isNaN(d.getTime())) return String(val)
    const pad = (n) => String(n).padStart(2, '0')
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
  } catch {
    return String(val)
  }
}

/** 日期格式化：'yyyy-MM-dd' */
export const formatDate = (val, def = '') => {
  const s = formatTime(val, def)
  return s ? s.slice(0, 10) : def
}
