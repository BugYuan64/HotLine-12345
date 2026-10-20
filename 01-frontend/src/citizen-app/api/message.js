import request from './request'

/** 消息列表（API P-N-01） */
export const getMessages = (page = 1, pageSize = 20) =>
  request.get('/public/messages', { params: { page, pageSize } })

/** 未读数（API P-N-01） */
export const getUnreadCount = () =>
  request.get('/public/messages/unread-count')

/** 标记单条已读（API P-N-02） */
export const markRead = (id) =>
  request.post(`/public/messages/${id}/read`)

/** 全部已读（API P-N-02） */
export const markAllRead = () =>
  request.post('/public/messages/read-all')

/** 查询通知设置（API P-N-03） */
export const getNotificationSettings = () =>
  request.get('/public/messages/settings')

/** 更新通知设置（API P-N-03） */
export const updateNotificationSettings = (data) =>
  request.put('/public/messages/settings', data)
