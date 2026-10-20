import request from './request'

/** 用户账号分页查询（API 5.2） */
export const getUsers = (params) =>
  request.get('/admin/users', { params })

/** 新增工作人员账号（API 5.2） */
export const createUser = (data) =>
  request.post('/admin/users', data)

/** 编辑工作人员账号（API 5.2） */
export const updateUser = (id, data) =>
  request.put(`/admin/users/${id}`, data)

/** 删除工作人员账号（API 5.2） */
export const deleteUser = (id) =>
  request.delete(`/admin/users/${id}`)

/** 账号状态切换（API 5.6） */
export const changeUserStatus = (id, status) =>
  request.post(`/admin/users/${id}/status`, { status })

/** 查询用户角色（GET 5.9） */
export const getUserRoles = (id) =>
  request.get(`/admin/users/${id}/roles`)

/** 用户角色分配（API 5.9） */
export const assignUserRoles = (id, role_ids) =>
  request.put(`/admin/users/${id}/roles`, { role_ids })

/** 角色列表查询（API 5.8） */
export const getRoles = () =>
  request.get('/admin/roles')

/** 菜单列表（角色权限配置界面数据源） */
export const getMenus = () =>
  request.get('/admin/menus')

/** 角色已配置菜单 */
export const getRoleMenus = (id) =>
  request.get(`/admin/roles/${id}/menus`)

/** 角色权限配置（API 5.10） */
export const assignRoleMenus = (id, menu_ids) =>
  request.put(`/admin/roles/${id}/menus`, { menu_ids })