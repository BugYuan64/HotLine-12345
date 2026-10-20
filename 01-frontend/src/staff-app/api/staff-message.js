import request from './request'

/** 部门端消息列表（/staff/messages） */
export const getMessages = (page = 1, pageSize = 20) =>
  request.get('/staff/messages', { params: { page, pageSize } })

/** 部门端未读数 */
export const getUnreadCount = () =>
  request.get('/staff/messages/unread-count')

/** 标记单条已读 */
export const markRead = (id) =>
  request.post(`/staff/messages/${id}/read`)

/** 全部已读 */
export const markAllRead = () =>
  request.post('/staff/messages/read-all')